package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.ValueInterval;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.picture.PictureType;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.FixProducer;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;
import jp.cobolinsight.fix.FixedFormatNormalizer;
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
 * R004 ON SIZE ERROR 句の欠如。ADD/SUBTRACT/MULTIPLY/DIVIDE/COMPUTE の算術文に ON SIZE ERROR 句が
 * 無く、かつ演算結果が受信項目の PICTURE 容量を超え得る箇所を検出する。全算術を一律に指摘せず、
 * 受信項目自身を被加算に含む累算(カウンタ・合計)は対象外とし、結果区間が受信の整数部桁容量を超え得る
 * (非有界を含む)場合のみ指摘する。結果区間は不動点結果(算術文の後続ノード入口)で照会する。
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

    @Override
    public String id() {
        return "R004";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("ON SIZE ERROR句の欠如", "例外処理")
                .summary("結果が受信項目の桁を超え得るのに ON SIZE ERROR 句を持たない"
                        + "算術文を検出します。")
                .rationale("桁あふれが起きても検知されず、上位桁を失った値が"
                        + "そのまま後続の計算と出力へ渡ります。")
                .detection("ADD・SUBTRACT・MULTIPLY・DIVIDE・COMPUTE のうち、ON SIZE ERROR 句が無く、"
                        + "区間値域解析による結果の範囲が受信項目の整数部の容量を超え得る"
                        + "(範囲が定まらない場合を含む)ものを検出します。"
                        + "受信項目自身を被加算に含む累算は対象外とします。")
                .remedy("ON SIZE ERROR 句を付けて桁あふれ時の処理を書くか、受信項目の桁を広げます。")
                .example("""
                        01  WS-RESULT  PIC 9(4).
                            COMPUTE WS-RESULT = WS-QTY * WS-PRICE.
                        """, """
                        01  WS-RESULT  PIC 9(4).
                            COMPUTE WS-RESULT = WS-QTY * WS-PRICE
                                ON SIZE ERROR PERFORM OVERFLOW-SHORI
                            END-COMPUTE.
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
                continue; // ON SIZE ERROR で防御済み
            }
            List<String> receivers = receivers(verb, text);
            if (receivers.isEmpty()) {
                continue; // 累算(受信を被加算に含む)などは対象外
            }
            if (receivers.stream().anyMatch(r -> resultMayOverflow(cfg, df, node, r, support))) {
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        verb + " 文に ON SIZE ERROR 句が無く、結果が受信項目の桁容量を超え得る。"
                                + "けたあふれが検知されない。",
                        new SourcePosition(model.sourceFile(), simple.range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    @Override
    public Optional<FixProducer> fixProducer() {
        return Optional.of(new OnSizeErrorFixProducer());
    }

    /**
     * ON SIZE ERROR 句を欠く算術文へ、DISPLAY ハンドラ付きの ON SIZE ERROR 句と END-句を付与する。
     * 句は算術文の内容終端(range.end)へ挿入する。ON SIZE ERROR 句は算術文のスコープ内の要素で
     * あり、END-句がスコープを閉じる。文が文末(直後に終止ピリオド)の場合、挿入は終止ピリオドの
     * 直前に入るため、ピリオドは自然に END-句の後へ回る。文が文の途中(直後に別の文)の場合は、
     * END-句が算術文のスコープを区切り、後続文はそのまま続く。Finding.location の行(=算術文の
     * 開始行)を anchor に対象文を再同定する。
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
            // 原ソースの終止ピリオドが挿入文の末尾へ回るため、その1バイトを桁予算から差し引く。
            List<String> layout = NORMALIZER.layoutStatement(clause, FixEdits.LAYOUT_CHARSET, 1);
            String replacement = "\n" + String.join("\n", layout);
            SourcePosition at = arithmetic.range().end();
            TextEdit edit = new TextEdit(new SourceRange(at, at), replacement);
            return Optional.of(new FixSuggestion("ON SIZE ERROR 句を付与する", List.of(edit)));
        }
    }

    /** 累算でない算術文の受信項目名。受信項目の現在値を演算対象に含む累算なら空リストを返す。 */
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
            // GIVING を持たない ADD/SUBTRACT などは、最終オペランドが送信と受信を兼ねる
            // 累算(カウンタ・合計)であり、対象外とする。
            return List.of();
        }
        List<String> targets = names(masked.substring(giving + "GIVING".length()));
        Set<String> source = new LinkedHashSet<>(names(masked.substring(0, giving)));
        return targets.stream().anyMatch(source::contains) ? List.of() : targets;
    }

    /** 算術文の流出(後続ノード入口)で受信項目区間が桁容量を超え得るか。 */
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
     * 受信項目の整数部が保持できる最大値。桁数が範囲外なら 0(=いかなる正値も超過)。範囲の上限
     * 18 桁は、標準COBOLの数字項目が保持できる最大桁数である。
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
