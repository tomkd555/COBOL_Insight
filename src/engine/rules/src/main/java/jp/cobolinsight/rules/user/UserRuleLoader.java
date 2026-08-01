package jp.cobolinsight.rules.user;

import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.json.JsonReader;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 利用者定義ルールの定義ファイル(JSON)を読み、{@link RegexUserRule} へ組み立てる。
 *
 * <p>定義は利用者が手で、または GUI の作成画面から書くため、1件の誤りで残り全部を捨てない。
 * 誤りのある定義だけを外し、理由を errors へ積んで返す。呼出側(CLI・GUI)はこれを利用者へ示す。
 *
 * <p>ID を U 始まりに限るのは、組み込みルール(R・S)との重複を定義の時点で断つためである。
 */
public final class UserRuleLoader {

    /** 扱える定義ファイルの版数。形式を変えるときに上げる。 */
    public static final long SUPPORTED_VERSION = 1L;

    /** 利用者定義ルールの ID。組み込みと衝突させないため U 始まりに限る。 */
    private static final Pattern ID_FORMAT = Pattern.compile("U[0-9A-Za-z_-]{1,15}");

    private static final String DEFAULT_CATEGORY = "利用者定義";

    private static final String DEFAULT_RATIONALE =
            "利用者が定義したルールである。定義に理由が書かれていない。";

    private static final String DEFAULT_REMEDY = "定義した検査の意図に沿って該当箇所を直す。";

    /** 読み込みの結果。誤りのあった定義は rules に含めず、理由を errors に持つ。 */
    public record LoadResult(List<Rule> rules, List<String> errors) {

        public LoadResult {
            rules = List.copyOf(rules);
            errors = List.copyOf(errors);
        }

        public boolean isEmpty() {
            return rules.isEmpty() && errors.isEmpty();
        }
    }

    private UserRuleLoader() {
    }

    /**
     * 定義ファイルを読む。ファイルが無い場合は誤りとせず、空の結果を返す。GUI は定義の有無に
     * かかわらず常に同じ位置を渡すため、未作成の状態が普通に起こる。
     */
    public static LoadResult load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new LoadResult(List.of(), List.of());
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new LoadResult(List.of(),
                    List.of(file + " を読めない: " + e.getMessage()));
        }
        return parse(json, file.toString());
    }

    /** JSON テキストから読む。sourceLabel は誤りの報告に添える定義の出所である。 */
    public static LoadResult parse(String json, String sourceLabel) {
        Map<String, Object> root;
        try {
            root = JsonReader.asObject(JsonReader.parse(json));
        } catch (IllegalArgumentException e) {
            return new LoadResult(List.of(), List.of(sourceLabel + ": " + e.getMessage()));
        }
        Object version = root.get("version");
        if (version != null && !Long.valueOf(SUPPORTED_VERSION).equals(version)) {
            return new LoadResult(List.of(), List.of(sourceLabel
                    + ": version は " + SUPPORTED_VERSION + " のみ扱える(指定値 " + version + ")"));
        }
        Object rulesValue = root.get("rules");
        if (rulesValue == null) {
            return new LoadResult(List.of(), List.of());
        }
        List<Object> elements;
        try {
            elements = JsonReader.asArray(rulesValue);
        } catch (IllegalArgumentException e) {
            return new LoadResult(List.of(), List.of(sourceLabel + ": rules は配列で書く"));
        }
        List<Rule> rules = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int index = 0; index < elements.size(); index++) {
            String where = sourceLabel + " の rules[" + index + "]";
            try {
                RegexUserRule rule = toRule(JsonReader.asObject(elements.get(index)));
                if (!seenIds.add(rule.id())) {
                    errors.add(where + ": ID が重複している: " + rule.id());
                    continue;
                }
                rules.add(rule);
            } catch (IllegalArgumentException e) {
                errors.add(where + ": " + e.getMessage());
            }
        }
        return new LoadResult(rules, errors);
    }

    private static RegexUserRule toRule(Map<String, Object> object) {
        String id = requireString(object, "id");
        if (!ID_FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException("id は U で始まり、英数字・ハイフン・下線が"
                    + "1〜15文字続く形にする(指定値 " + id + ")");
        }
        String name = requireString(object, "name");
        String patternText = requireString(object, "pattern");
        String message = requireString(object, "message");
        String category = optionalString(object, "category", DEFAULT_CATEGORY);
        boolean ignoreCase = optionalBoolean(object, "ignoreCase");
        boolean wholeLine = optionalBoolean(object, "wholeLine");
        Severity severity = toSeverity(optionalString(object, "severity", "MEDIUM"));
        Set<UserRuleTarget> targets = toTargets(object.get("targets"));
        Pattern pattern = compile(patternText, ignoreCase, "pattern");
        String excludeText = optionalString(object, "excludePattern", "");
        Pattern exclude = excludeText.isEmpty()
                ? null
                : compile(excludeText, ignoreCase, "excludePattern");
        RuleDoc doc = RuleDoc.named(name, category)
                .summary(optionalString(object, "summary",
                        "正規表現「" + patternText + "」に一致する行を検出する。"))
                .rationale(optionalString(object, "rationale", DEFAULT_RATIONALE))
                .detection(describeDetection(targets, patternText, ignoreCase, wholeLine,
                        excludeText))
                .remedy(optionalString(object, "remedy", DEFAULT_REMEDY))
                .example(optionalString(object, "badExample", ""),
                        optionalString(object, "goodExample", ""))
                .build();
        return new RegexUserRule(id, doc, severity, targets, pattern, exclude, message, wholeLine);
    }

    /**
     * 検出条件は定義の値から組む。利用者が書いた説明文をそのまま検出条件として載せると、
     * 正規表現を直したときに説明だけが古いまま残る。
     */
    private static String describeDetection(Set<UserRuleTarget> targets, String pattern,
            boolean ignoreCase, boolean wholeLine, String exclude) {
        StringBuilder out = new StringBuilder();
        out.append("対象は ")
                .append(targets.stream().map(Enum::name).collect(Collectors.joining("・")))
                .append(" の各行である。正規表現「").append(pattern)
                .append("」に一致する行を検出する(")
                .append(ignoreCase ? "大小を区別しない" : "大小を区別する")
                .append(")。");
        if (wholeLine) {
            out.append("行全体を対象とし、注記行も走査する。");
        } else {
            out.append("COBOL 本体とコピー句では注記行を除き、8〜72桁の範囲を対象とする。");
        }
        if (!exclude.isEmpty()) {
            out.append("同じ行が正規表現「").append(exclude).append("」にも一致する場合は除く。");
        }
        return out.toString();
    }

    private static Pattern compile(String text, boolean ignoreCase, String field) {
        try {
            return Pattern.compile(text, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    field + " の正規表現を解釈できない: " + e.getDescription());
        }
    }

    private static Severity toSeverity(String text) {
        try {
            return Severity.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("severity に扱えない値がある: " + text
                    + "(扱えるのは HIGH・MEDIUM・LOW・ADVISORY)");
        }
    }

    private static Set<UserRuleTarget> toTargets(Object value) {
        if (value == null) {
            return EnumSet.of(UserRuleTarget.COBOL);
        }
        List<Object> elements;
        try {
            elements = JsonReader.asArray(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("targets は配列で書く");
        }
        EnumSet<UserRuleTarget> targets = EnumSet.noneOf(UserRuleTarget.class);
        for (Object element : elements) {
            UserRuleTarget target = element instanceof String name
                    ? UserRuleTarget.of(name.toUpperCase(Locale.ROOT))
                    : null;
            if (target == null) {
                throw new IllegalArgumentException("targets に扱えない種別がある: " + element
                        + "(扱えるのは COBOL・COPYBOOK・BMS)");
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets が空である");
        }
        return targets;
    }

    private static String requireString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " が無い、または空である");
        }
        return text.strip();
    }

    private static String optionalString(Map<String, Object> object, String field,
            String fallback) {
        Object value = object.get(field);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " は文字列で書く");
        }
        return text.isBlank() ? fallback : text.strip();
    }

    private static boolean optionalBoolean(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(field + " は true か false で書く");
        }
        return flag;
    }
}
