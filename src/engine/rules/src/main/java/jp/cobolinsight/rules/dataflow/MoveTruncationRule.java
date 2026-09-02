package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.rules.cfg.CfgSupport;
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

    private static final RuleMeta META = RuleMeta.named("R003", "MOVE 文による切り捨て", "データ移動")
            .summary("受け取り側項目のけた数または文字長が送り出し側項目より小さい MOVE 文を検出します。")
            .rationale("数字項目では上位けたが、英数字項目では右端の文字が切り捨てられます。"
                    + "実行時エラーにはならないため、金額や識別子が別の値のまま処理が進みます。")
            .detection("送り出し側と受け取り側の PICTURE を解決し、数字項目どうしで受け取り側の"
                    + "整数部または小数部が短いもの、英数字項目どうしで送り出し側が長いものを検出します。"
                    + "表意定数・文字定数・集団項目・部分参照を送り出し側に含む MOVE 文と、"
                    + "両側の項類が異なる MOVE 文は対象外です。")
            .remedy("受け取り側項目の PICTURE を送り出し側項目以上に広げてください。"
                    + "切り捨てが意図なら、部分参照で転記する範囲を明示してください。")
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
            Truncation t = truncatingReceiver(simple.text(), support);
            if (t != null) {
                int line = simple.range().start().line();
                List<CodeFlowStep> steps = new ArrayList<>();
                support.item(t.sender()).ifPresent(item -> steps.add(CfgSupport.step(
                        item.position().file(), item.position().line(),
                        "送り出し側項目 " + t.sender() + " の宣言（PIC " + t.senderPic() + "）")));
                support.item(t.receiver()).ifPresent(item -> steps.add(CfgSupport.step(
                        item.position().file(), item.position().line(),
                        "受け取り側項目 " + t.receiver() + " の宣言（PIC " + t.receiverPic() + "）")));
                steps.add(CfgSupport.step(model.sourceFile(), line, "切り捨てが起きる MOVE 文"));
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        "MOVE " + t.sender() + " TO " + t.receiver() + " で" + t.lost()
                                + "が切り捨てられます。実行時エラーも警告も出ません。",
                        new SourcePosition(model.sourceFile(), line, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(new CodeFlow(steps)), List.of()));
            }
        }
    }

    /** What a truncating MOVE loses: both names, both PICTUREs, and the digits or characters lost. */
    private record Truncation(String sender, String senderPic, String receiver,
            String receiverPic, String lost) {
    }

    /** The first receiving item that loses digits, with the sender. null if none. */
    private static Truncation truncatingReceiver(String text, DataFlowSupport support) {
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
                return new Truncation(senderName, pictureOf(support, senderName), receiver,
                        pictureOf(support, receiver), lost(sender, recv));
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

    private static String pictureOf(DataFlowSupport support, String name) {
        return support.item(name).flatMap(DataItem::picture).orElse("?");
    }

    /** "上位 4 けた", "小数部 2 けた", "上位 4 けたと小数部 2 けた" or "右端 3 文字". */
    private static String lost(PictureType sender, PictureType recv) {
        if (!sender.isNumeric()) {
            return "右端 " + (sender.totalDigits() - recv.totalDigits()) + " 文字";
        }
        int high = sender.integerDigits() - recv.integerDigits();
        int low = sender.fractionDigits() - recv.fractionDigits();
        if (high > 0 && low > 0) {
            return "上位 " + high + " けたと小数部 " + low + " けた";
        }
        return high > 0 ? "上位 " + high + " けた" : "小数部 " + low + " けた";
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
