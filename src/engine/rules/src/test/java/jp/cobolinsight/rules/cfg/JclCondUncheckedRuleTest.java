package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R030 synthetic fixture verification for an unchecked preceding-step result in JCL (builds a JclJobModel directly). */
class JclCondUncheckedRuleTest {

    private static JclStep step(String name, int line, Optional<String> cond) {
        return new JclStep(name, JclExecKind.PGM, "PGM" + name, cond, List.of(),
                new SourcePosition("jcl/FIX030.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static jp.cobolinsight.core.spi.AnalysisContext context(JclJobModel job) {
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void detectsSubsequentStepWithoutCond() {
        JclJobModel job = new JclJobModel("FIX030", "jcl/FIX030.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.empty())));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(1, findings.size(),
                () -> "2番目以降のCOND無しステップ1件(先頭は対象外): " + findings);
        assertEquals("R030", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
    }

    @Test
    void ignoresSubsequentStepWithCond() {
        JclJobModel job = new JclJobModel("FIX030B", "jcl/FIX030.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("(4,LT,STEP010)"))));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(List.of(), findings, () -> "COND句を持つ後続ステップは対象外: " + findings);
    }

    private static JclStep call(String name, int line, Optional<String> cond) {
        return new JclStep(name, JclExecKind.PROC, "PRC" + name, cond, List.of(),
                new SourcePosition("jcl/FIX030.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    @Test
    void judgesExpandedProcStepsInsideTheProc() {
        // STEP010 calls a PROC whose first step is the job's first step; its second step has no
        // COND and neither has the call, so it is reported. STEP020 calls the same PROC under a
        // COND, which covers every step of that expansion.
        JclJobModel job = new JclJobModel("FIX030C", "jcl/FIX030.jcl", Optional.empty(),
                List.of(call("STEP010", 3, Optional.empty()),
                        step("STEP010.STEP1", 20, Optional.empty()),
                        step("STEP010.STEP2", 25, Optional.empty()),
                        call("STEP020", 8, Optional.of("(4,LT)")),
                        step("STEP020.STEP1", 20, Optional.empty()),
                        step("STEP020.STEP2", 25, Optional.empty())));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(List.of("STEP010.STEP2"),
                findings.stream().map(f -> f.message().split(" ")[0]).toList(),
                () -> findings.toString());
    }

    private static List<String> reportedSteps(List<Finding> findings) {
        return findings.stream().map(f -> f.message().split(" ")[0]).toList();
    }

    @Test
    void reportsAnUnguardedProcCallOnceForAllItsSteps() {
        JclJobModel job = new JclJobModel("FIX030D", "jcl/FIX030.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        call("STEP020", 8, Optional.empty()),
                        step("STEP020.STEP1", 20, Optional.empty()),
                        step("STEP020.STEP2", 25, Optional.empty()),
                        step("STEP020.STEP3", 30, Optional.empty())));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(List.of("STEP020"), reportedSteps(findings),
                () -> "one missing COND, one finding, at the call: " + findings);
    }

    @Test
    void aCondOnAnInnerProcCallCoversItsSteps() {
        JclJobModel job = new JclJobModel("FIX030E", "jcl/FIX030.jcl", Optional.empty(),
                List.of(call("STEP010", 3, Optional.empty()),
                        call("STEP010.PS1", 20, Optional.of("(0,NE)")),
                        step("STEP010.PS1.PS2", 40, Optional.empty()),
                        step("STEP010.PS1.PS3", 45, Optional.empty()),
                        step("STEP010.STEP9", 30, Optional.empty())));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(List.of("STEP010.STEP9"), reportedSteps(findings),
                () -> "PS3 sits under the inner call's COND; STEP9 under nothing: " + findings);
    }

    @Test
    void aStepExpandedFromAProcTwoJobsCallIsReportedOnce() {
        JclStep procStep = new JclStep("STEP020.STEP2", JclExecKind.PGM, "PGMX", Optional.empty(),
                List.of(), new SourcePosition("proc/FIXPRC.proc", 22, 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET));
        JclJobModel first = new JclJobModel("FIX030F", "jcl/FIX030F.jcl", Optional.empty(),
                List.of(call("STEP020", 3, Optional.empty()),
                        step("STEP020.STEP1", 20, Optional.empty()), procStep));
        JclJobModel second = new JclJobModel("FIX030G", "jcl/FIX030G.jcl", Optional.empty(),
                List.of(call("STEP020", 3, Optional.empty()),
                        step("STEP020.STEP1", 20, Optional.empty()), procStep));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(
                CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(first, second)));
        assertEquals(List.of("STEP020.STEP2"), reportedSteps(findings),
                () -> "the PROC line is one place, reported once: " + findings);
    }
}
