package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.ValueInterval;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
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
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;
import jp.cobolinsight.core.fix.FixedFormatNormalizer;
import jp.cobolinsight.rules.FixEdits;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R004 Missing ON SIZE ERROR clause. Detects an ADD/SUBTRACT/MULTIPLY/DIVIDE/COMPUTE arithmetic
 * statement that has no ON SIZE ERROR clause and whose result may exceed the receiving item's
 * PICTURE capacity. Rather than flagging every arithmetic statement uniformly, an accumulation
 * (counter/total) that includes the receiving item itself among the addends is excluded, and only
 * a case where the result interval may exceed the receiving item's integer-part digit capacity
 * (including an unbounded interval) is flagged. The result interval is queried from the fixed-point
 * result (the entry of the node following the arithmetic statement).
 */
public final class OnSizeErrorMissingRule implements Rule {

    private static final Pattern NAME_TOKEN =
            Pattern.compile("[\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*");
    private static final Set<String> ARITHMETIC_VERBS =
            Set.of("ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE");
    private static final Set<String> RESERVED = Set.of(
            "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "COMPUTE",
            "ROUNDED", "GIVING", "TO", "FROM", "BY", "INTO", "REMAINDER", "ON", "SIZE", "ERROR",
            "CORRESPONDING", "CORR", "NOT");

    private static final RuleMeta META = RuleMeta.named("R004", "ON SIZE ERROR句の欠如", "例外処理")
            .summary("結果が受け取り側項目のけた数を超え得るのに ON SIZE ERROR 句を持たない"
                    + "算術文を検出する。")
            .rationale("けたあふれが起きても検知されず、上位けたを失った値が"
                    + "そのまま後続の計算と出力に渡る。")
            .detection("ADD・SUBTRACT・MULTIPLY・DIVIDE・COMPUTE のうち、ON SIZE ERROR 句がなく、"
                    + "区間値域解析による結果の範囲が受け取り側項目の整数部のけた数を超え得る"
                    + "（範囲が定まらない場合を含む）ものを検出する。"
                    + "受け取り側項目自身を加数に含む累算は対象外とする。")
            .remedy("ON SIZE ERROR 句を付けてけたあふれ時の処理を書くか、受け取り側項目のけた数を広げる。")
            .example("""
                    01  WS-RESULT  PIC 9(4).
                        COMPUTE WS-RESULT = WS-QTY * WS-PRICE.
                    """, """
                    01  WS-RESULT  PIC 9(4).
                        COMPUTE WS-RESULT = WS-QTY * WS-PRICE
                            ON SIZE ERROR PERFORM OVERFLOW-SHORI
                        END-COMPUTE.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT, Command.REPORT, Command.FIX)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG, Needs.DATAFLOW, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        DataFlowFacts facts = context.artifact(DataFlowFacts.class).orElse(null);
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (facts == null || cfgs == null || texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            ProgramDataFlow df = facts.of(model).orElse(null);
            ControlFlowGraph cfg = cfgs.of(model).orElse(null);
            if (df != null && cfg != null) {
                evaluate(model, cfg, df, new DataFlowSupport(model, texts), findings);
            }
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, ProgramDataFlow df,
            DataFlowSupport support, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            Statement statement = node.statement().orElse(null);
            if (!(statement instanceof SimpleStatement simple)) {
                continue;
            }
            String verb = simple.verb().toUpperCase(Locale.ROOT);
            if (!ARITHMETIC_VERBS.contains(verb)) {
                continue;
            }
            String text = simple.text();
            if (text.toUpperCase(Locale.ROOT).contains("SIZE ERROR")) {
                continue; // already guarded by ON SIZE ERROR
            }
            List<String> receivers = receivers(verb, text);
            if (receivers.isEmpty()) {
                continue; // excluded: an accumulation (receiving item included among the addends), etc.
            }
            if (receivers.stream().anyMatch(r -> resultMayOverflow(cfg, df, node, r, support))) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        verb + " 文に ON SIZE ERROR 句がなく、結果が受け取り側項目のけた数を超え得る。"
                                + "けたあふれが検知されない。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    @Override
    public Optional<FixProducer> fix() {
        return Optional.of(new OnSizeErrorFixProducer());
    }

    /**
     * Attaches an ON SIZE ERROR clause with a DISPLAY handler, plus its END- clause, to an
     * arithmetic statement that lacks ON SIZE ERROR. The clause is inserted at the end of the
     * arithmetic statement's content (range.end). The ON SIZE ERROR clause is an element within the
     * arithmetic statement's scope, and the END- clause closes that scope. If the statement ends the
     * sentence (immediately followed by a terminating period), the insertion lands just before that
     * period, so the period naturally ends up after the END- clause. If the statement is mid-sentence
     * (immediately followed by another statement), the END- clause delimits the arithmetic
     * statement's scope and the following statement continues as-is. The target statement is
     * re-identified using the line from Finding.location (= the arithmetic statement's start line)
     * as the anchor.
     */
    private static final class OnSizeErrorFixProducer implements FixProducer {

        private static final FixedFormatNormalizer NORMALIZER = new FixedFormatNormalizer();

        @Override
        public Optional<FixSuggestion> produce(Finding finding, AnalysisContext context) {
            if (!"R004".equals(finding.ruleId())) {
                return Optional.empty();
            }
            CobolSemanticModel model =
                    FixEdits.modelOf(context, finding.location().file()).orElse(null);
            if (model == null) {
                return Optional.empty();
            }
            SimpleStatement arithmetic = FixEdits.findSimpleStatement(model,
                    finding.location().line(),
                    candidate -> ARITHMETIC_VERBS.contains(candidate.verb().toUpperCase(Locale.ROOT))
                            && !candidate.text().toUpperCase(Locale.ROOT).contains("SIZE ERROR"))
                    .orElse(null);
            if (arithmetic == null) {
                return Optional.empty();
            }
            String verb = arithmetic.verb().toUpperCase(Locale.ROOT);
            List<String> receivers = receivers(verb, arithmetic.text());
            if (receivers.isEmpty()) {
                return Optional.empty();
            }
            String clause = "ON SIZE ERROR DISPLAY 'SIZE ERROR: " + receivers.get(0)
                    + "' END-" + verb;
            // The original source's terminating period ends up after the inserted clause, so
            // subtract that one byte from the column budget.
            List<String> layout = NORMALIZER.layoutStatement(clause, FixEdits.LAYOUT_CHARSET, 1);
            String replacement = "\n" + String.join("\n", layout);
            SourcePosition at = arithmetic.range().end();
            TextEdit edit = new TextEdit(new SourceRange(at, at), replacement);
            return Optional.of(new FixSuggestion("ON SIZE ERROR 句を付ける", List.of(edit)));
        }
    }

    /** The receiving item names of an arithmetic statement that is not an accumulation. Returns an empty list if it is an accumulation that includes the receiving item's current value among the operands. */
    private static List<String> receivers(String verb, String text) {
        String masked = maskLiterals(text);
        if (verb.equals("COMPUTE")) {
            int eq = masked.indexOf('=');
            if (eq < 0) {
                return List.of();
            }
            List<String> targets = names(masked.substring(0, eq));
            Set<String> rhs = new LinkedHashSet<>(names(masked.substring(eq + 1)));
            return targets.stream().anyMatch(rhs::contains) ? List.of() : targets;
        }
        int giving = indexOfWord(masked.toUpperCase(Locale.ROOT), "GIVING");
        if (giving < 0) {
            // An ADD/SUBTRACT etc. without GIVING is an accumulation (counter/total) whose final
            // operand serves as both sender and receiver, and is excluded.
            return List.of();
        }
        List<String> targets = names(masked.substring(giving + "GIVING".length()));
        Set<String> source = new LinkedHashSet<>(names(masked.substring(0, giving)));
        return targets.stream().anyMatch(source::contains) ? List.of() : targets;
    }

    /** Whether the receiving item's interval, at the arithmetic statement's outflow (the entry of the following node), may exceed the digit capacity. */
    private static boolean resultMayOverflow(ControlFlowGraph cfg, ProgramDataFlow df, CfgNode node,
            String receiver, DataFlowSupport support) {
        PictureType pt = support.pictureType(receiver).orElse(null);
        if (pt == null || !pt.isNumeric()) {
            return false;
        }
        long capacity = capacity(pt);
        String name = receiver.toUpperCase(Locale.ROOT);
        for (CfgNode succ : cfg.successors(node)) {
            ValueInterval iv = df.intervalAt(succ, name).orElse(null);
            if (iv != null && iv.mayExceed(capacity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The maximum value the receiving item's integer part can hold. 0 if the digit count is out of
     * range (meaning any positive value exceeds it). The upper bound of 18 digits is the maximum
     * digit count a standard COBOL numeric item can hold.
     */
    private static long capacity(PictureType pt) {
        int digits = pt.integerDigits();
        if (digits <= 0 || digits > 18) {
            return 0L;
        }
        long r = 1;
        for (int i = 0; i < digits; i++) {
            r *= 10;
        }
        return r - 1;
    }

    private static List<String> names(String region) {
        List<String> out = new ArrayList<>();
        Matcher m = NAME_TOKEN.matcher(region);
        while (m.find()) {
            String tok = m.group().toUpperCase(Locale.ROOT);
            if (!RESERVED.contains(tok) && !out.contains(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    private static int indexOfWord(String upper, String word) {
        Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}$#_-])" + word + "(?![\\p{L}\\p{N}$#_-])")
                .matcher(upper);
        return m.find() ? m.start() : -1;
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
