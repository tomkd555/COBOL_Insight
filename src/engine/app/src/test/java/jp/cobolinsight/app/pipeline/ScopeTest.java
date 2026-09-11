package jp.cobolinsight.app.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The filter {@code lint --scope} applies to the walk: what is analysed, and what is a mistake. */
class ScopeTest {

    private static final List<String> WALKED =
            List.of("cobol/PGM1.cbl", "cobol/sub/PGM2.cbl", "copy/REC1.cpy", "jcl/JOB1.jcl");

    @Test
    void anEmptyScopeContainsTheWholeWalk() {
        Scope scope = Scope.ALL;
        assertTrue(scope.isEmpty());
        WALKED.forEach(relPath -> assertTrue(scope.contains(relPath), relPath));
    }

    @Test
    void aDirectoryScopeContainsWhatLiesUnderIt() {
        Scope scope = new Scope(List.of("cobol"));

        assertTrue(scope.contains("cobol/PGM1.cbl"));
        assertTrue(scope.contains("cobol/sub/PGM2.cbl"));
        assertFalse(scope.contains("copy/REC1.cpy"));
        assertFalse(scope.contains("cobolx/PGM3.cbl"), "前方一致だけでは範囲に入らないこと");
    }

    @Test
    void aFileScopeContainsThatFileAlone() {
        Scope scope = new Scope(List.of("cobol/PGM1.cbl"));

        assertTrue(scope.contains("cobol/PGM1.cbl"));
        assertFalse(scope.contains("cobol/sub/PGM2.cbl"));
    }

    @Test
    void separatorsAndCaseDoNotDecideTheMatch() {
        Scope scope = new Scope(List.of(".\\COBOL\\Sub\\"));

        assertTrue(scope.contains("cobol/sub/PGM2.cbl"));
        assertEquals(List.of("cobol/sub"), scope.paths());
    }

    @Test
    void severalScopesAreTakenTogether() {
        Scope scope = new Scope(List.of("cobol/sub", "jcl/JOB1.jcl"));

        assertTrue(scope.contains("cobol/sub/PGM2.cbl"));
        assertTrue(scope.contains("jcl/JOB1.jcl"));
        assertFalse(scope.contains("cobol/PGM1.cbl"));
    }

    /** The user is told the path back the way they typed it, not the way matching reads it. */
    @Test
    void aScopeThatNamesNothingInTheWalkIsReportedAsTheUserWroteIt() {
        Scope scope = new Scope(List.of("cobol", "Batch\\NoSuch.CBL"));

        assertEquals(List.of("Batch\\NoSuch.CBL"), scope.missing(WALKED));
    }

    @Test
    void aScopeThatNamesPartOfTheWalkIsNotMissing() {
        assertEquals(List.of(), new Scope(List.of("cobol/sub", "copy/REC1.cpy")).missing(WALKED));
    }

    /** Every spelling of the asset folder itself is the whole folder, which is no scope at all. */
    @Test
    void aScopeThatNamesTheAssetFolderItselfIsTheWholeFolder() {
        Scope scope = new Scope(List.of("", ".", "/", "./"));

        assertTrue(scope.isEmpty());
        assertEquals(List.of(), scope.written());
        WALKED.forEach(relPath -> assertTrue(scope.contains(relPath), relPath));
    }
}
