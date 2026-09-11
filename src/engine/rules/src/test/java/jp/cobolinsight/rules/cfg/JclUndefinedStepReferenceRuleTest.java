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

/** R047 a COND or IF that names a step no earlier step of the job defines. */
class JclUndefinedStepReferenceRuleTest {

    private static JclStep step(String name, int line, Optional<String> cond) {
        return new JclStep(name, JclExecKind.PGM, "PGM" + name, cond, List.of(),
                new SourcePosition("jcl/FIX047.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(JclJobModel job) {
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void reportsACondThatNamesAStepNoEarlierStepDefines() {
        JclJobModel job = new JclJobModel("FIX047", "jcl/FIX047.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(4,LT,STEP999)"))));
        List<Finding> findings = new JclUndefinedStepReferenceRule().evaluate(context(job));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals("R047", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
        assertEquals(true, findings.get(0).message().startsWith("STEP999"),
                findings.get(0).message());
    }

    @Test
    void ignoresACondThatNamesAnEarlierStep() {
        JclJobModel job = new JclJobModel("FIX047B", "jcl/FIX047.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("COND=(4,LT,STEP010)"))));
        assertEquals(List.of(), new JclUndefinedStepReferenceRule().evaluate(context(job)));
    }

    @Test
    void reportsAnIfConditionThatNamesAnUndefinedStep() {
        JclJobModel job = new JclJobModel("FIX047C", "jcl/FIX047.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("IF(STEP999.RC>4)THEN"))));
        List<Finding> findings = new JclUndefinedStepReferenceRule().evaluate(context(job));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertEquals(true, findings.get(0).message().startsWith("STEP999"),
                findings.get(0).message());
    }

    @Test
    void aStepInsideAProcMayNameAStepOfTheSameProcByItsLocalName() {
        // STEP030.STEP1 precedes STEP030.STEP2, which names it as plain "STEP1" as the PROC
        // body itself does.
        JclJobModel job = new JclJobModel("FIX047D", "jcl/FIX047.jcl", Optional.empty(),
                List.of(step("STEP030.STEP1", 20, Optional.empty()),
                        step("STEP030.STEP2", 25, Optional.of("COND=(0,NE,STEP1)"))));
        assertEquals(List.of(), new JclUndefinedStepReferenceRule().evaluate(context(job)));
    }
}
