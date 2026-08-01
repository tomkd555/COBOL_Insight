package jp.cobolinsight.rules.user;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 利用者が定義ファイルへ書いた正規表現を、復号済みソースの各行へ当てるルール。意味モデルを
 * 用いないため解析段階は SYNTAX とし、テキスト索引だけを参照する。
 *
 * <p>報告は1行につき1件に留める。同じ行の2件目以降まで挙げると、行単位の検査という利用者の
 * 見立てと件数が合わなくなる。
 */
public final class RegexUserRule implements Rule {

    /** メッセージの中でこの並びを書くと、一致した文字列へ置き換わる。 */
    private static final String MATCH_PLACEHOLDER = "${match}";

    /** 固定形式で本体が始まる桁(1起点で8桁目)。 */
    private static final int PROGRAM_AREA_START = 7;

    /** 固定形式で本体が終わる桁(1起点で72桁目)。 */
    private static final int PROGRAM_AREA_END = 72;

    private final String id;
    private final RuleDoc doc;
    private final Severity severity;
    private final Set<UserRuleTarget> targets;
    private final Pattern pattern;
    private final Pattern excludePattern;
    private final String message;
    private final boolean wholeLine;

    RegexUserRule(String id, RuleDoc doc, Severity severity, Set<UserRuleTarget> targets,
            Pattern pattern, Pattern excludePattern, String message, boolean wholeLine) {
        this.id = id;
        this.doc = doc;
        this.severity = severity;
        this.targets = Set.copyOf(targets);
        this.pattern = pattern;
        this.excludePattern = excludePattern;
        this.message = message;
        this.wholeLine = wholeLine;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public RuleDoc doc() {
        return doc;
    }

    @Override
    public Severity defaultSeverity() {
        return severity;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.SYNTAX;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : texts.entries().entrySet()) {
            UserRuleTarget target = UserRuleTarget.ofPath(entry.getKey());
            if (target == null || !targets.contains(target)) {
                continue;
            }
            scan(entry.getKey(), target, entry.getValue(), findings);
        }
        return findings;
    }

    private void scan(String path, UserRuleTarget target, String text, List<Finding> findings) {
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String scannable = scannableTextOf(lines[index].replace("\r", ""), target);
            if (scannable == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(scannable);
            if (!matcher.find()) {
                continue;
            }
            if (excludePattern != null && excludePattern.matcher(scannable).find()) {
                continue;
            }
            findings.add(Finding.of(id, severity.toLevel(),
                    message.replace(MATCH_PLACEHOLDER, matcher.group()),
                    new SourcePosition(path, index + 1, matcher.start() + 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /**
     * 1行のうち走査する範囲を返す。注記行には null を返す。COBOL 本体とコピー句では一連番号欄と
     * 標識欄を空白で置き換え、識別領域を切り落とす。空白で置き換えるのは、報告する桁位置を
     * 原ソースの桁と一致させるためである。BMS は固定形式の桁割りを持たないため行全体を返す。
     */
    private String scannableTextOf(String raw, UserRuleTarget target) {
        if (wholeLine || target == UserRuleTarget.BMS) {
            return raw;
        }
        if (raw.length() > 6) {
            char indicator = raw.charAt(6);
            if (indicator == '*' || indicator == '/') {
                return null;
            }
        }
        String line = raw.length() > PROGRAM_AREA_END ? raw.substring(0, PROGRAM_AREA_END) : raw;
        if (line.length() <= PROGRAM_AREA_START) {
            return line;
        }
        return " ".repeat(PROGRAM_AREA_START) + line.substring(PROGRAM_AREA_START);
    }
}
