package jp.cobolinsight.engineapi.jcl;

import jp.cobolinsight.engineapi.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JclModelTest {

    private static SourcePosition pos(int line) {
        return new SourcePosition("JOB1.jcl", line, 1, -1);
    }

    @Test
    void stepHoldsExecKindTargetAndDdStatements() {
        JclDdStatement dd = new JclDdStatement("SYSIN", Optional.of("SYK.INPUT.DATA"), pos(3));
        JclStep step = new JclStep("STEP01", JclExecKind.PGM, "SYKPGM1",
                Optional.of("(4,LT)"), List.of(dd), pos(2));
        assertEquals(JclExecKind.PGM, step.execKind());
        assertEquals("SYKPGM1", step.target());
        assertEquals("SYK.INPUT.DATA", step.ddStatements().get(0).datasetName().orElseThrow());
    }

    @Test
    void stepRejectsBlankTarget() {
        assertThrows(IllegalArgumentException.class,
                () -> new JclStep("STEP01", JclExecKind.PROC, " ", Optional.empty(), List.of(), pos(2)));
    }

    @Test
    void ddStatementAllowsMissingDatasetName() {
        JclDdStatement sysout = new JclDdStatement("SYSOUT", Optional.empty(), pos(4));
        assertTrue(sysout.datasetName().isEmpty());
    }

    @Test
    void jobModelAggregatesStepsImmutably() {
        JclStep step = new JclStep("STEP01", JclExecKind.PGM, "SYKPGM1",
                Optional.empty(), List.of(), pos(2));
        JclJobModel job = new JclJobModel("SYKJOB1", "JOB1.jcl", Optional.of("(8,LE)"), List.of(step));
        assertEquals("SYKJOB1", job.jobName());
        assertEquals("(8,LE)", job.condition().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> job.steps().clear());
    }
}
