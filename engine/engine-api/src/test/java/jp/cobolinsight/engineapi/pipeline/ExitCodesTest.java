package jp.cobolinsight.engineapi.pipeline;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExitCodesTest {

    private static Finding finding(FindingLevel level) {
        return Finding.of("R001", level, "msg", SourcePosition.fileStart("A.cbl"));
    }

    @Test
    void exitCodeConstantsFollowCliContract() {
        assertEquals(0, ExitCodes.SUCCESS);
        assertEquals(1, ExitCodes.WARNINGS);
        assertEquals(2, ExitCodes.ERRORS);
    }

    @Test
    void noFindingsMeansSuccess() {
        assertEquals(ExitCodes.SUCCESS, ExitCodes.fromFindings(List.of()));
    }

    @Test
    void noteOnlyFindingsMeanSuccess() {
        assertEquals(ExitCodes.SUCCESS, ExitCodes.fromFindings(List.of(finding(FindingLevel.NOTE))));
    }

    @Test
    void warningFindingMeansWarnings() {
        assertEquals(ExitCodes.WARNINGS, ExitCodes.fromFindings(
                List.of(finding(FindingLevel.NOTE), finding(FindingLevel.WARNING))));
    }

    @Test
    void errorFindingDominatesWarnings() {
        assertEquals(ExitCodes.ERRORS, ExitCodes.fromFindings(
                List.of(finding(FindingLevel.WARNING), finding(FindingLevel.ERROR))));
    }
}
