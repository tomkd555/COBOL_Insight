package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R003 MOVE による桁落ち・切り捨て。MOVE 文の送信項目と各受信項目の PICTURE を共有リゾルバで解決し、
 * 数値項目間で受信の整数部または小数部の桁数が送信より小さい切り捨て、英数字項目間で送信の文字長が
 * 受信より長いあふれを検出する。図形定数・文字列リテラル・集団項目・参照修飾を送信に含む MOVE、
 * および送受信の種別が異なる MOVE は対象外とする。
 */
public final class MoveTruncationRule implements Rule {

    private static final Pattern NAME_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*");

    @Override
    public String id() {
        return "R003";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("MOVEによる桁落ち・切り捨て", "データ移動")
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
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.DATA_FLOW;
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "MOVE で送信項目より桁数の小さい受信項目 " + truncated
                                + " へ移送している。桁落ち・切り捨てが起こる。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** 桁落ちする最初の受信項目名。無ければ null。 */
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
            return null; // 参照修飾は送信長が変わるため対象外
        }
        String senderName = firstName(senderRegion.substring("MOVE".length()));
        if (senderName == null) {
            return null; // 送信が図形定数・リテラル
        }
        PictureType sender = support.pictureType(senderName).orElse(null);
        if (sender == null) {
            return null; // 送信が集団項目・未解決
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
     * 送信から受信への移送で桁が失われるか。数字項目どうしは整数部と小数部を別に比べる。COBOL は
     * 小数点位置をそろえて移送するため、整数部が足りなければ上位桁が、小数部が足りなければ下位桁が
     * 落ちる。英数字項目どうしは PICTURE の文字数(totalDigits)を長さとして比べ、左詰めで移送した
     * ときに右端があふれる場合を桁落ちとみなす。種別が異なる組は判定しない。
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

    /** 空白で囲まれた語 word の開始位置。無ければ -1。 */
    private static int indexOfWord(String upper, String word) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}$#_-])" + word + "(?![\\p{L}\\p{N}$#_-])")
                .matcher(upper);
        return m.find() ? m.start() : -1;
    }

    /**
     * 括弧で囲む添字・参照修飾を空白へ置き換える。添字に使う変数は受信項目ではないため、
     * 桁比較の対象から外す必要がある。
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
