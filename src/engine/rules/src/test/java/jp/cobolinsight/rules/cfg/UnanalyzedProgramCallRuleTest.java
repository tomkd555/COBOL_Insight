package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R048 an EXECUTION/CALL/TRANSACTION_TRANSITION edge into a PROGRAM node the linker marked
 * {@code external=true}, on a hand-built CallGraph passed through {@code AnalysisContext.of}.
 */
class UnanalyzedProgramCallRuleTest {

    private static JclJobModel job(String name, String file, JclStep... steps) {
        return new JclJobModel(name, file, Optional.empty(), List.of(steps));
    }

    private static JclStep step(String name, int line, String file) {
        return new JclStep(name, JclExecKind.PGM, "PGM" + name, Optional.empty(), List.of(),
                new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static CobolSemanticModel program(String id, String file) {
        return new CobolSemanticModel(id, file, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    @Test
    void reportsAStepThatExecutesAProgramWithNoAnalysedSource() {
        JclJobModel job = job("JOBX", "jcl/FIX048.jcl", step("STEP010", 5, "jcl/FIX048.jcl"));
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("job:JOBX", NodeKind.JOB, "JOBX"),
                        new CallGraphNode("step:JOBX.STEP010", NodeKind.STEP, "STEP010"),
                        new CallGraphNode("program:EXTPGM", NodeKind.PROGRAM, "EXTPGM",
                                Map.of("external", "true"))),
                List.of(new CallGraphEdge("job:JOBX", "step:JOBX.STEP010", EdgeKind.EXECUTION,
                                Resolution.CONSTANT, 1, 5),
                        new CallGraphEdge("step:JOBX.STEP010", "program:EXTPGM",
                                EdgeKind.EXECUTION, Resolution.CONSTANT, 1, 5)));
        AnalysisContext context = AnalysisContext.of(List.of(), List.of(job), List.of(), List.of(),
                Optional.of(graph), Map.of());
        List<Finding> findings = new UnanalyzedProgramCallRule().evaluate(context);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R048", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertEquals("jcl/FIX048.jcl", findings.get(0).location().file());
        assertEquals(5, findings.get(0).location().line());
        assertEquals(true, findings.get(0).message().startsWith("EXTPGM"),
                findings.get(0).message());
    }

    /** A callee the scope of this run left out is in the folder, so there is nothing to report. */
    @Test
    void ignoresATargetTheScopeLeftOutOfTheRun() {
        JclJobModel job = job("JOBY", "jcl/FIX048S.jcl", step("STEP010", 5, "jcl/FIX048S.jcl"));
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("step:JOBY.STEP010", NodeKind.STEP, "STEP010"),
                        new CallGraphNode("program:OUTPGM", NodeKind.PROGRAM, "OUTPGM",
                                Map.of("external", "true", "outsideScope", "true"))),
                List.of(new CallGraphEdge("step:JOBY.STEP010", "program:OUTPGM",
                        EdgeKind.EXECUTION, Resolution.CONSTANT, 1, 5)));
        AnalysisContext context = AnalysisContext.of(List.of(), List.of(job), List.of(), List.of(),
                Optional.of(graph), Map.of());
        List<Finding> findings = new UnanalyzedProgramCallRule().evaluate(context);
        assertEquals(List.of(), findings);
    }

    /**
     * A step expanded out of a catalogued PROC keeps the line of its EXEC statement inside the
     * member, so the finding belongs in the member's file: the calling job is six lines long and
     * has no line 33.
     */
    @Test
    void placesAStepOfAnExpandedProcInTheMembersFile() {
        JclJobModel job = job("JOBZ", "jcl/FIX048F.jcl",
                step("COBRUN.LKED", 33, "jclproc/IGYWCL.jcl"));
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("job:JOBZ", NodeKind.JOB, "JOBZ"),
                        new CallGraphNode("step:JOBZ.COBRUN.LKED", NodeKind.STEP, "COBRUN.LKED"),
                        new CallGraphNode("program:IEWBLINK", NodeKind.PROGRAM, "IEWBLINK",
                                Map.of("external", "true"))),
                List.of(new CallGraphEdge("step:JOBZ.COBRUN.LKED", "program:IEWBLINK",
                        EdgeKind.EXECUTION, Resolution.CONSTANT, 1, 33)));
        AnalysisContext context = AnalysisContext.of(List.of(), List.of(job), List.of(), List.of(),
                Optional.of(graph), Map.of());
        List<Finding> findings = new UnanalyzedProgramCallRule().evaluate(context);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("jclproc/IGYWCL.jcl", findings.get(0).location().file());
        assertEquals(33, findings.get(0).location().line());
    }

    @Test
    void reportsAStaticCallToAProgramWithNoAnalysedSource() {
        CobolSemanticModel caller = program("CALLER", "cobol/FIX048B.cbl");
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("program:CALLER", NodeKind.PROGRAM, "CALLER"),
                        new CallGraphNode("program:EXTSUB", NodeKind.PROGRAM, "EXTSUB",
                                Map.of("external", "true"))),
                List.of(new CallGraphEdge("program:CALLER", "program:EXTSUB", EdgeKind.CALL,
                        Resolution.CONSTANT, 1, 10)));
        AnalysisContext context = AnalysisContext.of(List.of(caller), List.of(), List.of(),
                List.of(), Optional.of(graph), Map.of());
        List<Finding> findings = new UnanalyzedProgramCallRule().evaluate(context);
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("cobol/FIX048B.cbl", findings.get(0).location().file());
        assertEquals(10, findings.get(0).location().line());
        assertEquals(true, findings.get(0).message().startsWith("EXTSUB"),
                findings.get(0).message());
    }

    @Test
    void ignoresATargetResolvedInsideTheFolder() {
        CobolSemanticModel caller = program("CALLER", "cobol/FIX048C.cbl");
        CobolSemanticModel callee = program("CALLEE", "cobol/FIX048D.cbl");
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("program:CALLER", NodeKind.PROGRAM, "CALLER"),
                        new CallGraphNode("program:CALLEE", NodeKind.PROGRAM, "CALLEE")),
                List.of(new CallGraphEdge("program:CALLER", "program:CALLEE", EdgeKind.CALL,
                        Resolution.CONSTANT, 1, 10)));
        AnalysisContext context = AnalysisContext.of(List.of(caller, callee), List.of(), List.of(),
                List.of(), Optional.of(graph), Map.of());
        assertEquals(List.of(), new UnanalyzedProgramCallRule().evaluate(context));
    }

    @Test
    void ignoresAnExternalUtilityTarget() {
        JclJobModel job = job("JOBY", "jcl/FIX048E.jcl", step("STEP010", 5, "jcl/FIX048E.jcl"));
        CallGraph graph = new CallGraph(
                List.of(new CallGraphNode("job:JOBY", NodeKind.JOB, "JOBY"),
                        new CallGraphNode("step:JOBY.STEP010", NodeKind.STEP, "STEP010"),
                        new CallGraphNode("utility:SORT", NodeKind.EXTERNAL_UTILITY, "SORT",
                                Map.of("utility", "SORT"))),
                List.of(new CallGraphEdge("job:JOBY", "step:JOBY.STEP010", EdgeKind.EXECUTION,
                                Resolution.CONSTANT, 1, 5),
                        new CallGraphEdge("step:JOBY.STEP010", "utility:SORT", EdgeKind.EXECUTION,
                                Resolution.CONSTANT, 1, 5)));
        AnalysisContext context = AnalysisContext.of(List.of(), List.of(job), List.of(), List.of(),
                Optional.of(graph), Map.of());
        assertEquals(List.of(), new UnanalyzedProgramCallRule().evaluate(context));
    }
}
