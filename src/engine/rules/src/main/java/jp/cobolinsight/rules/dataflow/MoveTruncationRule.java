package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R003 Digit loss / truncation from MOVE. Resolves the PICTURE of a MOVE statement's sending item
 * and each receiving item through the shared resolver, and detects: between numeric items,
 * truncation where the receiving item's integer or fractional digit count is smaller than the
 * sending item's; and between alphanumeric items, overflow where the sending item's character
 * length is longer than the receiving item's. A MOVE whose sending side is a figurative constant,
 * a string literal, a group item, or a reference modification, and a MOVE whose sending and
 * receiving items differ in kind, are excluded.
 */
public final class MoveTruncationRule implements Rule {

    private static final Pattern NAME_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*");

    private static final RuleMeta META = RuleMeta.named("R003", "MOVEによる桁落ち・切り捨て", "データ移動")
            .summary("受信項目の桁数・文字長が送信項目より小さい MOVE を検出します。")
            .rationale("数値では上位桁が、英数字では末尾の文字が失われます。"
                    + "実行時の異常にはならないため、金額や識別子が黙って別の値になります。")
            .detection("送受信の PICTURE を解決し、数値項目どうしで受信の整数部または小数部が"
                    + "送信より短いもの、英数字項目どうしで送信が受信より長いものを検出します。"
                    + "図形定数・文字列リテラル・集団項目・参照修飾を送信に含む MOVE と、"
                    + "送受信の種別が異なる MOVE は対象外とします。")
            .remedy("受信項目の PICTURE を送信項目以上に広げます。"
                    + "切り捨てが意図なら、参照修飾で切り出す範囲を明示します。")
            .example("""
                    01  WS-AMT-IN   PIC 9(9).
                    01  WS-AMT-OUT  PIC 9(5).
                        MOVE WS-AMT-IN TO WS-AMT-OUT.
                    """, """
                    01  WS-AMT-IN   PIC 9(9).
                    01  WS-AMT-OUT  PIC 9(9).
                        MOVE WS-AMT-IN TO WS-AMT-OUT.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (cfgs == null || texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (cfg != null) {
                evaluate(model, cfg, new DataFlowSupport(model, texts), findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, DataFlowSupport support,
            List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (!(statement instanceof SimpleStatement simple)
                    || !"MOVE".equals(simple.verb().toUpperCase(Locale.ROOT))) {
                continue;
            }
            String truncated = truncatingReceiver(simple.text(), support);
            if (truncated != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "MOVE で送信項目より桁数の小さい受信項目 " + truncated
                                + " へ移送している。桁落ち・切り捨てが起こる。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** The name of the first receiving item that loses digits. null if none. */
    private static String truncatingReceiver(String text, DataFlowSupport support) {
        String masked = maskLiterals(text);
        String upper = masked.toUpperCase(Locale.ROOT);
        if (upper.contains(" CORRESPONDING ") || upper.contains(" CORR ")) {
            return null;
        }
        int to = indexOfWord(upper, "TO");
        if (to < 0) {
            return null;
        }
        String senderRegion = masked.substring(0, to);
        if (senderRegion.indexOf(':') >= 0) {
            return null; // excluded: a reference modification changes the sending length
        }
        String senderName = firstName(senderRegion.substring("MOVE".length()));
        if (senderName == null) {
            return null; // the sending side is a figurative constant or literal
        }
        PictureType sender = support.pictureType(senderName).orElse(null);
        if (sender == null) {
            return null; // the sending side is a group item or unresolved
        }
        Matcher m = NAME_TOKEN.matcher(
                maskParenthesized(masked.substring(to + "TO".length())));
        while (m.find()) {
            String receiver = m.group();
            PictureType recv = support.pictureType(receiver).orElse(null);
            if (recv != null && truncates(sender, recv)) {
                return receiver;
            }
        }
        return null;
    }

    /**
     * Whether digits are lost when moving from the sending item to the receiving item. Between
     * numeric items, the integer and fractional parts are compared separately. Because COBOL moves
     * data by aligning the decimal point, insufficient integer digits lose the high-order digits,
     * and insufficient fractional digits lose the low-order digits. Between alphanumeric items, the
     * PICTURE character count (totalDigits) is compared as the length, and a case where the right
     * end overflows when moved left-justified is treated as digit loss. A pair of differing kinds
     * is not judged.
     */
    private static boolean truncates(PictureType sender, PictureType recv) {
        if (sender.isNumeric() && recv.isNumeric()) {
            return recv.integerDigits() < sender.integerDigits()
                    || recv.fractionDigits() < sender.fractionDigits();
        }
        if (!sender.isNumeric() && !recv.isNumeric()) {
            return sender.totalDigits() > recv.totalDigits();
        }
        return false;
    }

    private static String firstName(String region) {
        Matcher m = NAME_TOKEN.matcher(region);
        return m.find() ? m.group() : null;
    }

    /** The start position of the word `word` surrounded by whitespace. -1 if none. */
    private static int indexOfWord(String upper, String word) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}$#_-])" + word + "(?![\\p{L}\\p{N}$#_-])")
                .matcher(upper);
        return m.find() ? m.start() : -1;
    }

    /**
     * Replaces a subscript or reference modification enclosed in parentheses with spaces. Since a
     * variable used as a subscript is not a receiving item, it must be excluded from the digit
     * comparison.
     */
    private static String maskParenthesized(String region) {
        StringBuilder sb = new StringBuilder(region);
        int depth = 0;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                continue;
            }
            sb.setCharAt(i, ' ');
        }
        return sb.toString();
    }

    private static String maskLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
