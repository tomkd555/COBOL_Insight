package jp.cobolinsight.rules.custom;

import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.json.JsonReader;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Builds a {@link Rule} from one {@code custom} entry of {@code rules.json}.
 *
 * <p>Every problem is reported by throwing {@link IllegalArgumentException} with a message the
 * caller shows to the author; the caller drops that one entry and keeps the rest. Messages are
 * Japanese because they are read by the same people who read the rest of the tool.
 *
 * <p>IDs are restricted to {@code U…} so that a custom rule can never collide with a built-in one.
 */
public final class CustomRules {

    /** Custom rule IDs. The U prefix keeps them clear of the built-in R and S ranges. */
    private static final Pattern ID_FORMAT = Pattern.compile("U[0-9A-Za-z_-]{1,15}");

    private static final String DEFAULT_CATEGORY = "利用者定義";

    private static final String DEFAULT_RATIONALE =
            "利用者が定義したルールです。定義に理由が書かれていません。";

    private static final String DEFAULT_REMEDY = "定義した検査の意図に沿って該当箇所を直します。";

    private CustomRules() {
    }

    public static Rule of(Map<String, Object> object) {
        String id = requireString(object, "id");
        if (!ID_FORMAT.matcher(id).matches()) {
            throw new IllegalArgumentException("id は U で始まり、英数字・ハイフン・下線が"
                    + "1〜15文字続く形にする(指定値 " + id + ")");
        }
        String name = requireString(object, "name");
        String message = requireString(object, "message");
        Map<String, Object> match = requireObject(object, "match");
        String kind = requireString(match, "kind");
        Set<AssetKind> targets = toTargets(object.get("targets"));

        return switch (kind) {
            case "line" -> lineRule(object, match, id, name, message, targets);
            case "statement" -> statementRule(object, match, id, name, message, targets);
            case "checked-after" -> checkedAfterRule(object, match, id, name, message, targets);
            default -> throw new IllegalArgumentException("match.kind に扱えない値がある: " + kind
                    + "(扱えるのは line・statement・checked-after)");
        };
    }

    // ---- line ----

    private static Rule lineRule(Map<String, Object> object, Map<String, Object> match, String id,
            String name, String message, Set<AssetKind> targets) {
        String regex = requireString(match, "regex");
        boolean ignoreCase = optionalBoolean(match, "ignoreCase");
        String area = optionalString(match, "area", "programArea");
        if (!area.equals("programArea") && !area.equals("wholeLine")) {
            throw new IllegalArgumentException("match.area に扱えない値がある: " + area
                    + "(扱えるのは programArea・wholeLine)");
        }
        boolean wholeLine = area.equals("wholeLine");
        String excludeText = optionalString(match, "excludeRegex", "");
        Pattern exclude = excludeText.isEmpty() ? null
                : compile(excludeText, ignoreCase, "match.excludeRegex");
        RuleMeta meta = metaOf(object, id, name, targets,
                "正規表現「" + regex + "」に一致する行を検出します。",
                describeLine(targets, regex, ignoreCase, wholeLine, excludeText),
                Set.of(Needs.SOURCE_TEXT));
        return new LineRule(meta, compile(regex, ignoreCase, "match.regex"), exclude, message,
                wholeLine);
    }

    private static String describeLine(Set<AssetKind> targets, String regex, boolean ignoreCase,
            boolean wholeLine, String exclude) {
        StringBuilder out = new StringBuilder();
        out.append("対象は ").append(labelOf(targets))
                .append(" の各行です。正規表現「").append(regex)
                .append("」に一致する行を検出します(")
                .append(ignoreCase ? "大小を区別しません" : "大小を区別します")
                .append(")。");
        if (wholeLine) {
            out.append("行全体を対象とし、注記行も走査します。");
        } else {
            out.append("COBOL 本体とコピー句では注記行を除き、8〜72桁の範囲を対象とします。");
        }
        if (!exclude.isEmpty()) {
            out.append("同じ行が正規表現「").append(exclude).append("」にも一致する場合は除きます。");
        }
        return out.toString();
    }

    // ---- statement ----

    private static Rule statementRule(Map<String, Object> object, Map<String, Object> match,
            String id, String name, String message, Set<AssetKind> targets) {
        List<String> verbs = requireStrings(match, "verb").stream()
                .map(verb -> verb.toUpperCase(Locale.ROOT)).toList();
        List<String> clauses = optionalStrings(match, "missingClause").stream()
                .map(clause -> clause.toUpperCase(Locale.ROOT)).toList();
        String inParagraphText = optionalString(match, "inParagraph", "");
        Pattern inParagraph = inParagraphText.isEmpty() ? null
                : compile(inParagraphText, true, "match.inParagraph");
        RuleMeta meta = metaOf(object, id, name, targets,
                String.join("・", verbs) + " 文を検出します。",
                describeStatement(verbs, clauses, inParagraphText), Set.of(Needs.SEMANTIC));
        return new StatementRule(meta, verbs, clauses, inParagraph, message);
    }

    private static String describeStatement(List<String> verbs, List<String> clauses,
            String inParagraph) {
        StringBuilder out = new StringBuilder("対象は ").append(String.join("・", verbs))
                .append(" 文です。");
        if (clauses.isEmpty()) {
            out.append("該当する文をすべて検出します。");
        } else {
            out.append(String.join("・", clauses)).append(" のいずれの句も伴わないものを検出します。");
        }
        if (!inParagraph.isEmpty()) {
            out.append("段落名が正規表現「").append(inParagraph).append("」に一致する段落だけを見ます。");
        }
        return out.toString();
    }

    // ---- checked-after ----

    private static Rule checkedAfterRule(Map<String, Object> object, Map<String, Object> match,
            String id, String name, String message, Set<AssetKind> targets) {
        Map<String, Object> after = requireObject(match, "after");
        String verb = requireString(after, "verb");
        String textRegex = optionalString(after, "textRegex", "");
        Pattern afterText = textRegex.isEmpty() ? null
                : compile(textRegex, false, "match.after.textRegex");
        Map<String, Object> checks = requireObject(match, "checks");
        List<String> dataItems = toDataItems(checks.get("dataItem"));
        CheckedAfterRule.Scope scope = toScope(optionalString(match, "scope",
                "untilNextMatchingStatement"));
        boolean onEveryPath = optionalBoolean(match, "onEveryPath");
        RuleMeta meta = metaOf(object, id, name, targets,
                verb + " の実行後に " + String.join("・", dataItems) + " を検査しない箇所を検出します。",
                describeCheckedAfter(verb, textRegex, dataItems, scope, onEveryPath),
                Set.of(Needs.SEMANTIC, Needs.CFG));
        return new CheckedAfterRule(meta, verb, afterText, dataItems, scope, onEveryPath, message);
    }

    private static String describeCheckedAfter(String verb, String textRegex,
            List<String> dataItems, CheckedAfterRule.Scope scope, boolean onEveryPath) {
        StringBuilder out = new StringBuilder("対象は ").append(verb).append(" 文");
        if (!textRegex.isEmpty()) {
            out.append("(本文が正規表現「").append(textRegex).append("」に一致するもの)");
        }
        out.append("です。その実行後、").append(switch (scope) {
            case UNTIL_NEXT_MATCHING_STATEMENT -> "次の同じ動詞の文に達するまで";
            case UNTIL_PARAGRAPH_END -> "段落の終わりまで";
            case UNTIL_PROGRAM_END -> "プログラムの終わりまで";
        }).append("の前方経路で ").append(String.join("・", dataItems))
                .append(" を条件参照しないものを検出します(")
                .append(onEveryPath ? "すべての経路で検査を要する" : "いずれかの経路に検査があれば足りる")
                .append(")。");
        return out.toString();
    }

    private static CheckedAfterRule.Scope toScope(String text) {
        return switch (text) {
            case "untilNextMatchingStatement" ->
                    CheckedAfterRule.Scope.UNTIL_NEXT_MATCHING_STATEMENT;
            case "untilParagraphEnd" -> CheckedAfterRule.Scope.UNTIL_PARAGRAPH_END;
            case "untilProgramEnd" -> CheckedAfterRule.Scope.UNTIL_PROGRAM_END;
            default -> throw new IllegalArgumentException("match.scope に扱えない値がある: " + text
                    + "(扱えるのは untilNextMatchingStatement・untilParagraphEnd・untilProgramEnd)");
        };
    }

    private static List<String> toDataItems(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof String single) {
            items.add(single.strip());
        } else if (value != null) {
            for (Object element : JsonReader.asArray(value)) {
                if (!(element instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("checks.dataItem は空でない文字列で書く");
                }
                items.add(text.strip());
            }
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("checks.dataItem が無い、または空である");
        }
        return items;
    }

    // ---- shared ----

    private static RuleMeta metaOf(Map<String, Object> object, String id, String name,
            Set<AssetKind> targets, String defaultSummary, String detection, Set<Needs> needs) {
        return RuleMeta.named(id, name, optionalString(object, "category", DEFAULT_CATEGORY))
                .summary(optionalString(object, "summary", defaultSummary))
                .rationale(optionalString(object, "rationale", DEFAULT_RATIONALE))
                .detection(detection)
                .remedy(optionalString(object, "remedy", DEFAULT_REMEDY))
                .example(optionalString(object, "badExample", ""),
                        optionalString(object, "goodExample", ""))
                .severity(toSeverity(optionalString(object, "severity", "MEDIUM")))
                .commands(toCommands(object.get("commands")))
                .targets(targets)
                .needs(needs)
                .build();
    }

    private static Severity toSeverity(String text) {
        try {
            return Severity.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("severity に扱えない値がある: " + text
                    + "(扱えるのは HIGH・MEDIUM・LOW・ADVISORY)");
        }
    }

    /** Absent means lint and report, which is where a hand-written rule was run before V2. */
    private static Set<Command> toCommands(Object value) {
        if (value == null) {
            return Set.of(Command.LINT, Command.REPORT);
        }
        EnumSet<Command> commands = EnumSet.noneOf(Command.class);
        for (Object element : asArray(value, "commands")) {
            if (!(element instanceof String text)) {
                throw new IllegalArgumentException("commands に扱えない値がある: " + element);
            }
            try {
                commands.add(Command.valueOf(text.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("commands に扱えない値がある: " + text
                        + "(扱えるのは LINT・SQL_LINT・REPORT・FIX・SCAN)");
            }
        }
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("commands が空である");
        }
        return commands;
    }

    private static Set<AssetKind> toTargets(Object value) {
        if (value == null) {
            return Set.of(AssetKind.COBOL);
        }
        EnumSet<AssetKind> targets = EnumSet.noneOf(AssetKind.class);
        for (Object element : asArray(value, "targets")) {
            if (!(element instanceof String text)) {
                throw new IllegalArgumentException("targets に扱えない種別がある: " + element);
            }
            try {
                targets.add(AssetKind.valueOf(text.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("targets に扱えない種別がある: " + text
                        + "(扱えるのは COBOL・COPYBOOK・BMS・JCL)");
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("targets が空である");
        }
        return targets;
    }

    private static String labelOf(Set<AssetKind> targets) {
        return targets.stream().map(Enum::name).sorted().collect(Collectors.joining("・"));
    }

    private static Pattern compile(String text, boolean ignoreCase, String field) {
        try {
            return Pattern.compile(text, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    field + " の正規表現を解釈できない: " + e.getDescription());
        }
    }

    private static List<Object> asArray(Object value, String field) {
        try {
            return JsonReader.asArray(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " は配列で書く");
        }
    }

    private static Map<String, Object> requireObject(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            throw new IllegalArgumentException(field + " が無い");
        }
        try {
            return JsonReader.asObject(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " はオブジェクトで書く");
        }
    }

    private static String requireString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " が無い、または空である");
        }
        return text.strip();
    }

    private static List<String> requireStrings(Map<String, Object> object, String field) {
        List<String> values = optionalStrings(object, field);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(field + " が無い、または空である");
        }
        return values;
    }

    private static List<String> optionalStrings(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (value == null) {
            return List.of();
        }
        if (value instanceof String single) {
            return single.isBlank() ? List.of() : List.of(single.strip());
        }
        List<String> values = new ArrayList<>();
        for (Object element : asArray(value, field)) {
            if (!(element instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(field + " は空でない文字列の配列で書く");
            }
            values.add(text.strip());
        }
        return values;
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
