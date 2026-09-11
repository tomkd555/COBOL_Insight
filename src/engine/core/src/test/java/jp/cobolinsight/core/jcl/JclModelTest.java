package jp.cobolinsight.core.jcl;

import jp.cobolinsight.core.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        JclDdStatement dd = new JclDdStatement("SYSIN", Optional.of("SYK.INPUT.DATA"),
                Optional.of("SHR"), pos(3));
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
        JclDdStatement sysout = new JclDdStatement("SYSOUT", Optional.empty(), Optional.empty(),
                pos(4));
        assertTrue(sysout.datasetName().isEmpty());
        assertTrue(sysout.dispositionText().isEmpty());
    }

    @Test
    void jobModelAggregatesStepsImmutably() {
        JclStep step = new JclStep("STEP01", JclExecKind.PGM, "SYKPGM1",
                Optional.empty(), List.of(), pos(2));
        JclJobModel job = new JclJobModel("SYKJOB1", "JOB1.jcl", Optional.of("(8,LE)"), List.of(step));
        assertEquals("SYKJOB1", job.jobName());
        assertEquals("(8,LE)", job.condition().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> job.steps().clear());
        assertTrue(job.syschk().isEmpty(), "a job with nothing but its steps owns no SYSCHK");
        assertEquals(List.of(), job.missingMembers());
        assertEquals(1, job.position().line(), "a job nobody read the JOB card of stands at line 1");
        assertEquals("JOB1.jcl", job.position().file());
    }

    /** A missing member says which statement named it, and only a PROC or an INCLUDE can. */
    @Test
    void memberMissNamesTheStatementThatLookedForIt() {
        JclMemberMiss miss = new JclMemberMiss(7, "include", "CI302");
        assertEquals(JclMemberMiss.INCLUDE, miss.kind(), "the kind is read in upper case");
        assertEquals("CI302", miss.name());
        assertThrows(IllegalArgumentException.class, () -> new JclMemberMiss(7, "SET", "CI302"));
        assertThrows(IllegalArgumentException.class,
                () -> new JclMemberMiss(7, JclMemberMiss.PROC, " "));
    }

    /** A data set with no name at all, and a disposition with no status, are neither of them one. */
    @Test
    void theNewRecordsRejectAValueTheyCannotDoWithout() {
        assertThrows(IllegalArgumentException.class, () -> new JclDataset(" "));
        assertThrows(IllegalArgumentException.class, () -> new JclDisposition(" ",
                Optional.empty(), Optional.empty(), "SHR"));
        assertThrows(IllegalArgumentException.class, () -> new JclOverrideMiss(3, " "));
        assertThrows(IllegalArgumentException.class, () -> new JclReferbackMiss(3, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new JclUtilityFacts.ProgramRun(" ", Optional.empty(), Optional.empty(),
                        Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new JclUtilityFacts.TableUse(" ", JclUtilityFacts.DatasetAccess.READ));
    }

    /** The status JCL assumes where the author left it out, which {@code DISP=(,PASS)} does. */
    @Test
    void dispositionNamesTheStatusJclAssumes() {
        JclDisposition omitted = new JclDisposition(JclDisposition.DEFAULT_STATUS,
                Optional.of("PASS"), Optional.empty(), "(,PASS)");
        assertEquals("NEW", omitted.status());
        assertEquals("(,PASS)", omitted.raw(), "the parameter as written is kept beside it");
    }

    /** A concatenation entry is numbered from the named DD, so there is no entry before it. */
    @Test
    void ddStatementRejectsANegativeConcatIndex() {
        assertThrows(IllegalArgumentException.class, () -> new JclDdStatement("IN1",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, Map.of(), Map.of(), List.of(), -1, pos(3)));
    }

    /** Every map of the model is a copy nobody outside it can change. */
    @Test
    void everyMapOfTheModelIsUnmodifiable() {
        JclDdStatement dd = new JclDdStatement("IN1", Optional.of("A.B"),
                Optional.of(new JclDataset("A.B")), Optional.of("SHR"), Optional.empty(),
                Optional.empty(), false, Map.of("DSN", "A.B"), Map.of("DSN", "*.DD1"), List.of(),
                0, pos(3));
        assertThrows(UnsupportedOperationException.class, () -> dd.parameters().clear());
        assertThrows(UnsupportedOperationException.class, () -> dd.referbacks().clear());

        JclStep step = new JclStep("STEP01", JclExecKind.PGM, "SYKPGM1", Optional.empty(),
                List.of(dd), Map.of("COND", "(4,LT)"), Optional.empty(), Optional.empty(),
                Optional.empty(), pos(2));
        assertThrows(UnsupportedOperationException.class, () -> step.parameters().clear());
    }

    /** The DD roles are read in the order the cards named them, so the copy keeps that order. */
    @Test
    void utilityFactsKeepTheOrderTheirDdRolesWereGivenIn() {
        Map<String, JclUtilityFacts.DatasetAccess> roles = new LinkedHashMap<>();
        roles.put("SORTIN", JclUtilityFacts.DatasetAccess.READ);
        roles.put("SORTOUT", JclUtilityFacts.DatasetAccess.WRITE);
        roles.put("SYSOUT", JclUtilityFacts.DatasetAccess.WRITE);
        JclUtilityFacts facts =
                new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), roles);

        assertEquals(List.of("SORTIN", "SORTOUT", "SYSOUT"), List.copyOf(facts.ddRoles().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> facts.ddRoles().clear());
        assertTrue(new JclUtilityFacts(List.of(), List.of(), List.of(), List.of(), Map.of())
                .isEmpty(), "facts that say nothing at all are empty");
    }
}
