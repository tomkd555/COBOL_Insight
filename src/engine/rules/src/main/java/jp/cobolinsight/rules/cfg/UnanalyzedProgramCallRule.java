package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * R048 A call-graph edge (EXECUTION, CALL or TRANSACTION_TRANSITION) into a PROGRAM node the
 * linker could not match to an analysed source ({@code external=true}, set by
 * {@code CallGraphLinker.programAttributes} when the target name is not among the analysed
 * programs). An {@code EXTERNAL_UTILITY} target (DFSORT, IDCAMS, ...) is a different node kind
 * and is not reported, and neither is a target carrying {@code outsideScope=true}: this run's
 * {@code --scope} left part of the COBOL unread, so whether the asset folder holds that source is
 * not known. A scoped run marks every unresolved target that way, which makes this rule silent
 * under a scope: only a whole-folder run can say that a callee's source is nowhere in the folder.
 *
 * <p>Reported at the edge's call site. A {@code step:} node is placed at the step's own position,
 * which for a step expanded out of a catalogued PROC names the member the EXEC statement stands in
 * rather than the job that calls it; a {@code job:} node is placed in the job's file and a
 * {@code program:} node in the program's file, both at the edge's line. A step the job models do
 * not carry falls back to its job's file, the way the other two are placed. An edge whose line is
 * unknown, or whose "from" node is none of these three kinds, is not reported: there is nowhere to
 * place it.
 */
public final class UnanalyzedProgramCallRule implements Rule {

    private static final String JOB_PREFIX = "job:";
    private static final String STEP_PREFIX = "step:";
    private static final String PROGRAM_PREFIX = "program:";
    private static final Set<EdgeKind> WATCHED_KINDS =
            Set.of(EdgeKind.EXECUTION, EdgeKind.CALL, EdgeKind.TRANSACTION_TRANSITION);

    private static final RuleMeta META =
            RuleMeta.named("R048", "解析対象にないプログラムの呼び出し", "呼び出し関係")
            .summary("実行・CALL・トランザクション遷移の呼び出し先のうち、解析対象の"
                    + "フォルダーに原始プログラムがないプログラムを検出します。")
            .rationale("呼び出し先の中身を検査できないため、そのプログラムに関わる不具合は"
                    + "この解析の対象から漏れます。")
            .detection("呼び出し関係グラフの EXECUTION・CALL・TRANSACTION_TRANSITION の"
                    + "エッジのうち、呼び出し先が解析対象のフォルダーに原始プログラムのない"
                    + "プログラムのものを検出します。DFSORT・IDCAMS などのユーティリティは"
                    + "対象外です。")
            .remedy("呼び出し先の原始プログラムを解析対象のフォルダーに含めるか、"
                    + "プログラム名に誤りがないか確認してください。")
            .example("""
                    //STEP020  EXEC PGM=FLB0301
                    """, """
                    //STEP020  EXEC PGM=FLB030
                    """)
            .severity(Severity.LOW)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL, AssetKind.JCL)
            .needs(Needs.CALL_GRAPH)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        CallGraph graph = context.callGraph().orElse(null);
        if (graph == null) {
            return List.of();
        }
        Map<String, CallGraphNode> byId = new LinkedHashMap<>();
        for (CallGraphNode node : graph.nodes()) {
            byId.put(node.id(), node);
        }
        Map<String, String> jobFiles = new LinkedHashMap<>();
        Map<String, SourcePosition> stepPositions = new LinkedHashMap<>();
        for (JclJobModel job : context.jclJobs()) {
            jobFiles.put(job.jobName(), job.sourceFile());
            for (JclStep step : job.steps()) {
                stepPositions.putIfAbsent(STEP_PREFIX + job.jobName() + "." + step.name(),
                        step.position());
            }
        }
        Map<String, String> programFiles = new LinkedHashMap<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            programFiles.put(model.programId().toUpperCase(Locale.ROOT), model.sourceFile());
        }
        List<Finding> findings = new ArrayList<>();
        for (CallGraphEdge edge : graph.edges()) {
            if (!WATCHED_KINDS.contains(edge.kind()) || edge.line() == null) {
                continue;
            }
            CallGraphNode target = byId.get(edge.toId());
            if (target == null || target.kind() != NodeKind.PROGRAM
                    || !"true".equals(target.attributes().get("external"))
                    || "true".equals(target.attributes().get("outsideScope"))) {
                continue;
            }
            SourcePosition position =
                    positionOf(edge, stepPositions, jobFiles, programFiles);
            if (position == null) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    target.label() + " は、解析対象のフォルダーに原始プログラムが"
                            + "ありません。この呼び出し先は解析の対象外です。",
                    position));
        }
        return findings;
    }

    /**
     * Where the call stands. A step is placed at its own position, because a step expanded out of a
     * catalogued PROC carries the line of its EXEC statement inside the member: pairing that line
     * with the calling job's file points at a line the job does not have. Any other node is placed
     * in its own source file at the edge's line.
     */
    private static SourcePosition positionOf(CallGraphEdge edge,
            Map<String, SourcePosition> stepPositions, Map<String, String> jobFiles,
            Map<String, String> programFiles) {
        SourcePosition step = stepPositions.get(edge.fromId());
        if (step != null) {
            return new SourcePosition(step.file(), step.line(), 1,
                    SourcePosition.UNKNOWN_BYTE_OFFSET);
        }
        String file = sourceFileOf(edge.fromId(), jobFiles, programFiles);
        return file == null ? null
                : new SourcePosition(file, edge.line(), 1, SourcePosition.UNKNOWN_BYTE_OFFSET);
    }

    private static String sourceFileOf(String fromId, Map<String, String> jobFiles,
            Map<String, String> programFiles) {
        if (fromId.startsWith(STEP_PREFIX)) {
            String rest = fromId.substring(STEP_PREFIX.length());
            int dot = rest.indexOf('.');
            String jobName = dot < 0 ? rest : rest.substring(0, dot);
            return jobFiles.get(jobName);
        }
        if (fromId.startsWith(JOB_PREFIX)) {
            return jobFiles.get(fromId.substring(JOB_PREFIX.length()));
        }
        if (fromId.startsWith(PROGRAM_PREFIX)) {
            return programFiles.get(fromId.substring(PROGRAM_PREFIX.length()));
        }
        return null;
    }
}
