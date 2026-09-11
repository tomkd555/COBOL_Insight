package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
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
 * R016 Overflow of the receiving area in STRING/UNSTRING. Using the shared resolver's byte lengths,
 * detects a configuration where, in a STRING statement, the total length of the concatenated
 * sending items and literals exceeds the receiving item's length, or, in an UNSTRING statement, the
 * sending item's length exceeds the total of all receiving items after splitting and cannot be held.
 * A statement containing an item whose length cannot be resolved is excluded.
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

    private static final RuleMeta META =
            RuleMeta.named("R016", "STRING/UNSTRING 文の受け取り側項目のあふれ", "データ移動")
                    .summary("STRING の連結結果が受け取り側項目に収まらない、または UNSTRING の"
                            + "送り出し側項目が受け取り側項目群に収まらない構成を検出します。")
                    .rationale("収まらない分が切り捨てられ、"
                            + "連結した文字列や分割した結果が途中で欠けます。")
                    .detection("送り出し側の合計長と受け取り側の長さをバイト長で突き合わせ、"
                            + "超えるものを検出します。長さを解決できない項目を含む文と、"
                            + "ON OVERFLOW 句であふれ時の処理を書いてある文は対象外です。")
                    .remedy("受け取り側項目の長さを広げるか、"
                            + "ON OVERFLOW 句であふれ時の処理を書いてください。")
                    .example("""
                            01  WS-OUT  PIC X(10).
                                STRING WS-A WS-B DELIMITED BY SIZE INTO WS-OUT.
                            """, """
                            01  WS-OUT  PIC X(30).
                                STRING WS-A WS-B DELIMITED BY SIZE INTO WS-OUT
                                    ON OVERFLOW PERFORM OVERFLOW-SHORI
                                END-STRING.
                            """)
                    .severity(Severity.HIGH)
                    .commands(Command.LINT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.CFG, Needs.SOURCE_TEXT)
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
                evaluate(model, cfg, DataFlowSupport.of(model, texts), findings);
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
            if (hasOverflowHandler(simple.text())) {
                continue;
            }
            String message = "STRING".equals(verb) ? checkString(simple.text(), support)
                    : "UNSTRING".equals(verb) ? checkUnstring(simple.text(), support)
                    : null;
            if (message != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    /**
     * Whether the statement has an ON OVERFLOW clause. Even though overflow can still occur, if
     * handling for the overflow case is written, truncation does not pass silently. Since this is
     * exactly the remedy for this rule, such statements are excluded.
     */
    private static boolean hasOverflowHandler(String text) {
        return wordIndex(text, "OVERFLOW") >= 0;
    }

    /** The warning message when the STRING sending total length exceeds the receiving length; null otherwise. */
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
        return receiver + "（" + capacity + "バイト）に STRING の結果 " + total
                + "バイトが収まりません。あふれた分が切り捨てられます。";
    }

    /** The warning message when the UNSTRING sending length exceeds the total receiving length after splitting; null otherwise. */
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
        return sourceName + "（" + sourceLen + "バイト）が受け取り側項目の合計 " + receiverTotal
                + "バイトを超えています。分割結果を保持しきれません。";
    }

    /**
     * Sums the operand lengths within region. A string literal adds its character count; a data
     * name adds its resolved byte length. A name whose length cannot be resolved, such as a verb
     * word or a clause keyword, is skipped. null (undecidable) if there are no operands at all.
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
                // skip a subscript or reference modification
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
