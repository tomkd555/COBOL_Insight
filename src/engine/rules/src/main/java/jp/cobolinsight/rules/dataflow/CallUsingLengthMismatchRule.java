package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R041 A CALL whose USING operands do not match the callee's PROCEDURE DIVISION USING in count, or
 * pass an item shorter than the callee's corresponding LINKAGE item. The callee is resolved through
 * the call graph, which already names the target of both a static CALL (by literal) and a dynamic
 * CALL the linker resolved by constant propagation (see {@code CallGraphLinker.linkCalls}); this
 * rule matches a program-to-program {@code CALL/CONSTANT} edge back to its call site by line and
 * reads the operand list from the CALL statement's own text.
 *
 * <p>The plan lists this rule's needs as SEMANTIC and CALL_GRAPH; ordering the callee's PROCEDURE
 * DIVISION USING items requires the raw source text (nothing in the semantic model orders them), so
 * this implementation also declares SOURCE_TEXT.
 *
 * <p>ponytail: a CALL statement that mentions BY VALUE anywhere in its USING clause is skipped
 * outright rather than tracked per operand, since passing convention is not otherwise recorded
 * per-operand here and none of the three fixture folders uses BY VALUE; add per-operand tracking
 * if a fixture needs it.
 */
public final class CallUsingLengthMismatchRule implements Rule {

    private static final String PROGRAM_PREFIX = "program:";
    private static final Pattern PROCEDURE_DIVISION_HEADER =
            Pattern.compile("(?is)\\bPROCEDURE\\s+DIVISION\\b(.*?)\\.");
    private static final Set<String> USING_MARKER = Set.of("BY", "REFERENCE", "CONTENT", "VALUE");

    private static final RuleMeta META = RuleMeta
            .named("R041", "CALL の USING と呼び出し先の LINKAGE の長さ不一致", "呼び出し関係")
            .summary("CALL 文の USING の作用対象を、呼び出し先の PROCEDURE DIVISION USING が並べる"
                    + "連絡節項目と個数・長さで突き合わせ、一致しないものを検出します。")
            .rationale("個数が合わなければ呼び出し先の連絡節項目の一部に何も転記されず、"
                    + "渡す長さが足りなければ呼び出し先が定義域を超えて読み書きします。")
            .detection("静的 CALL と、リンカーが定数伝播で解決した動的 CALL を対象にします。"
                    + "CALL 文の USING に並ぶ作用対象について、個数と各項目の長さを、呼び出し先の"
                    + "PROCEDURE DIVISION USING が並べる連絡節項目と突き合わせます。"
                    + "個数が一致しないものと、呼び出し先の項目が渡す項目より長いものを検出します。"
                    + "BY VALUE を含む CALL 文、解析対象にない呼び出し先、"
                    + "リンカーが解決できない呼び出し先は対象外です。")
            .remedy("呼び出し先の連絡節と同じ個数・長さの項目を渡すか、"
                    + "呼び出し先の PROCEDURE DIVISION USING を実際に渡す項目に合わせてください。")
            .example("""
                    CALL 'FLS030' USING WS-SHORT-AREA.
                    """, """
                    CALL 'FLS030' USING WS-LONG-AREA.
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CALL_GRAPH, Needs.SOURCE_TEXT)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        CallGraph graph = context.callGraph().orElse(null);
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (graph == null || texts == null) {
            return List.of();
        }
        Map<String, CobolSemanticModel> byProgramId = new LinkedHashMap<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            byProgramId.putIfAbsent(model.programId().toUpperCase(Locale.ROOT), model);
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            String callerId = PROGRAM_PREFIX + model.programId().toUpperCase(Locale.ROOT);
            DataFlowSupport callerSupport = DataFlowSupport.of(model, texts);
            List<Statement> all = new ArrayList<>();
            for (Procedure procedure : model.procedures()) {
                all.addAll(procedure.statements());
            }
            NumericClassSupport.walk(all, statement -> evaluate(model, statement, callerId,
                    callerSupport, graph, byProgramId, texts, findings));
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, Statement statement, String callerId,
            DataFlowSupport callerSupport, CallGraph graph, Map<String, CobolSemanticModel> byProgramId,
            SourceTextIndex texts, List<Finding> findings) {
        if (!(statement instanceof SimpleStatement simple)
                || !"CALL".equals(simple.verb().toUpperCase(Locale.ROOT))) {
            return;
        }
        String text = simple.text().replaceAll("\\s+", " ");
        if (NumericClassSupport.indexOfWord(text.toUpperCase(Locale.ROOT), "VALUE") >= 0) {
            return; // BY VALUE: not tracked per operand here, see class javadoc
        }
        List<String> operands = usingOperands(text, "ON", "END-CALL");
        int line = simple.range().start().line();
        for (CallGraphEdge edge : graph.edges()) {
            if (edge.kind() != EdgeKind.CALL || edge.resolution() == Resolution.UNRESOLVED
                    || !edge.fromId().equals(callerId) || edge.line() == null
                    || edge.line() != line || !edge.toId().startsWith(PROGRAM_PREFIX)) {
                continue;
            }
            String calleeName = edge.toId().substring(PROGRAM_PREFIX.length());
            CobolSemanticModel callee = byProgramId.get(calleeName);
            if (callee == null) {
                continue; // unresolved or out-of-folder target
            }
            List<String> params = calleeUsingParams(callee, texts);
            if (params == null) {
                continue;
            }
            String message = mismatch(operands, params, callerSupport,
                    DataFlowSupport.of(callee, texts), calleeName);
            if (message != null) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                        new SourcePosition(model.sourceFile(), line, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
                return;
            }
        }
    }

    private static String mismatch(List<String> operands, List<String> params,
            DataFlowSupport callerSupport, DataFlowSupport calleeSupport, String calleeName) {
        if (operands.size() != params.size()) {
            return calleeName + " の連絡節は CALL の USING と個数が一致しません。"
                    + "呼び出し側は " + operands.size() + " 個、呼び出し先は " + params.size() + " 個です。";
        }
        for (int i = 0; i < operands.size(); i++) {
            Integer passedLength = callerSupport.byteLength(operands.get(i)).orElse(null);
            Integer paramLength = calleeSupport.byteLength(params.get(i)).orElse(null);
            if (passedLength != null && paramLength != null && paramLength > passedLength) {
                return calleeName + " の連絡節項目 " + params.get(i) + "（" + paramLength
                        + "バイト）が渡す項目 " + operands.get(i) + "（" + passedLength
                        + "バイト）より長くなっています。";
            }
        }
        return null;
    }

    /** The ordered USING operands of a CALL statement's own text, up to the first of stopWords. */
    private static List<String> usingOperands(String statementText, String... stopWords) {
        String masked = NumericClassSupport.maskParenthesized(
                NumericClassSupport.maskLiterals(statementText));
        String upper = masked.toUpperCase(Locale.ROOT);
        int using = NumericClassSupport.indexOfWord(upper, "USING");
        if (using < 0) {
            return List.of();
        }
        String rest = masked.substring(using + "USING".length());
        String restUpper = upper.substring(using + "USING".length());
        int stop = restUpper.length();
        for (String stopWord : stopWords) {
            int idx = NumericClassSupport.indexOfWord(restUpper, stopWord);
            if (idx >= 0) {
                stop = Math.min(stop, idx);
            }
        }
        return tokens(rest.substring(0, stop));
    }

    /** The ordered USING parameters the callee's PROCEDURE DIVISION USING declares. null if PROCEDURE DIVISION cannot be found. */
    private static List<String> calleeUsingParams(CobolSemanticModel model, SourceTextIndex texts) {
        String source = texts.textOf(model.sourceFile()).orElse(null);
        if (source == null) {
            return null;
        }
        Matcher header = PROCEDURE_DIVISION_HEADER.matcher(source);
        if (!header.find()) {
            return null;
        }
        String masked = NumericClassSupport.maskParenthesized(
                NumericClassSupport.maskLiterals(header.group(1)));
        String upper = masked.toUpperCase(Locale.ROOT);
        int using = NumericClassSupport.indexOfWord(upper, "USING");
        if (using < 0) {
            return List.of();
        }
        String rest = masked.substring(using + "USING".length());
        String restUpper = upper.substring(using + "USING".length());
        int returning = NumericClassSupport.indexOfWord(restUpper, "RETURNING");
        return tokens(returning < 0 ? rest : rest.substring(0, returning));
    }

    private static List<String> tokens(String region) {
        List<String> names = new ArrayList<>();
        Matcher m = NumericClassSupport.NAME_TOKEN.matcher(region);
        while (m.find()) {
            String token = m.group();
            if (!USING_MARKER.contains(token.toUpperCase(Locale.ROOT))) {
                names.add(token);
            }
        }
        return names;
    }
}
