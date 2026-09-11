package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R046 the last step of a job flushed by its own COND on a fully successful run. */
class JclFinalStepSkippedOnSuccessRuleTest {

    private static JclStep step(String name, int line, Optional<String> cond) {
        return new JclStep(name, JclExecKind.PGM, "PGM" + name, cond, List.of(),
                new SourcePosition("jcl/FIX046.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(JclJobModel job) {
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void reportsALastStepBypassedWheneverThePrecedingStepSucceeds() {
        JclJobModel job = new JclJobModel("FIX046", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(0,EQ)"))));
        List<Finding> findings = new JclFinalStepSkippedOnSuccessRule().evaluate(context(job));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R046", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
        assertEquals(true, findings.get(0).message().startsWith("STEP020"), findings.get(0).message());
    }

    @Test
    void ignoresTheNormalRunOnlyOnSuccessGuard() {
        // COND=(0,NE): bypassed when the preceding step's RC is not 0, so it runs on success.
        JclJobModel job = new JclJobModel("FIX046B", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(0,NE)"))));
        assertEquals(List.of(), new JclFinalStepSkippedOnSuccessRule().evaluate(context(job)),
                "COND=(0,NE) runs the step on success; not the pattern this rule flags");
    }

    @Test
    void ignoresTheRunUnlessBadlyFailedGuard() {
        // COND=(4,LT,STEP010): bypassed when 4 < RC, so a return code of 0 runs the step.
        JclJobModel job = new JclJobModel("FIX046E", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(4,LT,STEP010)"))));
        assertEquals(List.of(), new JclFinalStepSkippedOnSuccessRule().evaluate(context(job)),
                "COND=(4,LT) bypasses the step only above return code 4");
    }

    @Test
    void reportsAGreaterThanTestThatHoldsAtZero() {
        // COND=(4,GT): bypassed when 4 > RC, which is true at return code 0.
        JclJobModel job = new JclJobModel("FIX046F", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(4,GT)"))));
        assertEquals(1, new JclFinalStepSkippedOnSuccessRule().evaluate(context(job)).size(),
                "COND=(4,GT) bypasses the step at every return code below 4, including 0");
    }

    @Test
    void ignoresAOneStepJob() {
        JclJobModel job = new JclJobModel("FIX046C", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.of("COND=(0,EQ)"))));
        assertEquals(List.of(), new JclFinalStepSkippedOnSuccessRule().evaluate(context(job)),
                "a job with a single step has no preceding step to be bypassed on");
    }

    @Test
    void ignoresALastStepGuardedByAnEnclosingIfInsteadOfItsOwnCond() {
        JclJobModel job = new JclJobModel("FIX046D", "jcl/FIX046.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("IF(STEP010.RC=0)THEN"))));
        assertEquals(List.of(), new JclFinalStepSkippedOnSuccessRule().evaluate(context(job)),
                "an enclosing IF is not the step's own COND");
    }
}
