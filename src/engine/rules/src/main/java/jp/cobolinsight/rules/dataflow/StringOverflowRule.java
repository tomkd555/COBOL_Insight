package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
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
 * R016 STRING/UNSTRING の受信領域あふれ。STRING 文で連結する送信項目・リテラルの合計長が受信項目長を
 * 超える構成、UNSTRING 文で送信項目長が分割後の全受信項目長の合計を超え保持しきれない構成を、共有
 * リゾルバのバイト長で検出する。長さを解決できない項目を含む文は対象外とする。
 */
public final class StringOverflowRule implements Rule {

    private static final Pattern NAME_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*");
    private static final Pattern DELIMITED_BY = Pattern.compile(
            "(?i)DELIMITED\\s+BY\\s+(SIZE|'[^']*'|\"[^\"]*\"|[\\p{L}\\p{N}$#_-]+)");
    private static final Pattern UNSTRING_CLAUSE = Pattern.compile(
            "(?i)\\b(DELIMITER|COUNT)\\s+IN\\s+[\\p{L}\\p{N}$#_-]+"
                    + "|(?i)\\bWITH\\s+POINTER\\s+[\\p{L}\\p{N}$#_-]+"
                    + "|(?i)\\bTALLYING\\s+IN\\s+[\\p{L}\\p{N}$#_-]+");

    @Override
    public String id() {
        return "R016";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("STRING/UNSTRING文の受信領域あふれ", "データ移動")
                .summary("STRING の連結結果が受信項目に収まらない、または UNSTRING の"
                        + "送信項目が受信項目群に収まらない構成を検出します。")
                .rationale("収まらない分が切り捨てられ、"
                        + "連結した文字列や分割した結果が途中で欠けます。")
                .detection("送信側の合計長と受信側の長さをバイト長で突き合わせ、"
                        + "超えるものを検出します。長さを解決できない項目を含む文は対象外とします。")
                .remedy("受信項目の長さを広げるか、ON OVERFLOW 句であふれ時の処理を書きます。")
                .example("""
                        01  WS-OUT  PIC X(10).
                            STRING WS-A WS-B DELIMITED BY SIZE INTO WS-OUT.
                        """, """
                        01  WS-OUT  PIC X(30).
                            STRING WS-A WS-B DELIMITED BY SIZE INTO WS-OUT
                                ON OVERFLOW PERFORM OVERFLOW-SHORI
                            END-STRING.
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
            if (!(statement instanceof SimpleStatement simple)) {
                continue;
            }
            String verb = simple.verb().toUpperCase(Locale.ROOT);
            String message = "STRING".equals(verb) ? checkString(simple.text(), support)
                    : "UNSTRING".equals(verb) ? checkUnstring(simple.text(), support)
                    : null;
            if (message != null) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(), message,
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /** STRING の送信合計長 > 受信長 のとき警告文言、そうでなければ null。 */
    private static String checkString(String text, DataFlowSupport support) {
        int into = wordIndex(text, "INTO");
        if (into < 0) {
            return null;
        }
        String sourceRegion = DELIMITED_BY.matcher(text.substring(0, into)).replaceAll(" ");
        Integer total = sumOperandLengths(sourceRegion, support);
        String receiver = firstName(text.substring(into + "INTO".length()));
        Integer capacity = receiver == null ? null : support.byteLength(receiver).orElse(null);
        if (total == null || capacity == null || total <= capacity) {
            return null;
        }
        return "STRING の送信項目合計長 " + total + " が受信項目 " + receiver + " の長さ " + capacity
                + " を超える。受信領域あふれが起こる。";
    }

    /** UNSTRING の送信長 > 分割後の全受信長合計 のとき警告文言、そうでなければ null。 */
    private static String checkUnstring(String text, DataFlowSupport support) {
        int into = wordIndex(text, "INTO");
        if (into < 0) {
            return null;
        }
        String sourceRegion = DELIMITED_BY.matcher(text.substring(0, into)).replaceAll(" ");
        String sourceName = firstName(sourceRegion.substring("UNSTRING".length()));
        Integer sourceLen = sourceName == null ? null : support.byteLength(sourceName).orElse(null);
        String receiverRegion = UNSTRING_CLAUSE.matcher(text.substring(into + "INTO".length()))
                .replaceAll(" ");
        int overflow = wordIndex(receiverRegion, "OVERFLOW");
        if (overflow >= 0) {
            receiverRegion = receiverRegion.substring(0, overflow);
        }
        Integer receiverTotal = sumOperandLengths(receiverRegion, support);
        if (sourceLen == null || receiverTotal == null || sourceLen <= receiverTotal) {
            return null;
        }
        return "UNSTRING の送信項目 " + sourceName + " の長さ " + sourceLen
                + " が分割後の受信項目合計長 " + receiverTotal + " を超える。分割結果を保持しきれない。";
    }

    /**
     * region 内のオペランド長を合計する。文字列リテラルはその文字数、データ名は解決できたバイト長を
     * 加える。動詞語・句キーワードなど長さを解決できない名前は読み飛ばす。オペランドが1つも無ければ
     * null(判定不能)。
     */
    private static Integer sumOperandLengths(String region, DataFlowSupport support) {
        int total = 0;
        boolean any = false;
        int i = 0;
        while (i < region.length()) {
            char c = region.charAt(i);
            if (c == '\'' || c == '"') {
                int close = region.indexOf(c, i + 1);
                if (close < 0) {
                    return any ? total : null;
                }
                total += close - (i + 1);
                any = true;
                i = close + 1;
            } else if (isNameStart(c)) {
                int j = i;
                while (j < region.length() && isNameChar(region.charAt(j))) {
                    j++;
                }
                String tok = region.substring(i, j);
                i = j;
                Integer len = NAME_TOKEN.matcher(tok).matches()
                        ? support.byteLength(tok).orElse(null) : null;
                if (len != null) {
                    total += len;
                    any = true;
                }
                // 添字・参照修飾は読み飛ばす
                while (i < region.length() && region.charAt(i) == '(') {
                    int depth = 0;
                    while (i < region.length()) {
                        char d = region.charAt(i++);
                        if (d == '(') {
                            depth++;
                        } else if (d == ')' && --depth == 0) {
                            break;
                        }
                    }
                }
            } else {
                i++;
            }
        }
        return any ? total : null;
    }

    private static String firstName(String region) {
        Matcher m = NAME_TOKEN.matcher(region);
        return m.find() ? m.group() : null;
    }

    private static int wordIndex(String text, String word) {
        Matcher m = Pattern.compile("(?i)(?<![\\p{L}\\p{N}$#_-])" + word + "(?![\\p{L}\\p{N}$#_-])")
                .matcher(text);
        return m.find() ? m.start() : -1;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '$' || c == '#' || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '$' || c == '#' || c == '_';
    }
}
