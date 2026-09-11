package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R052 a DD the program's SELECT names that the step running it does not allocate. */
class JclMissingDdForProgramRuleTest {

    private static final String JCL = "jcl/FIX052.jcl";
    private static final String COBOL = "cobol/FIX052.cbl";

    private static FileDefinition file(String name, int line) {
        return file(name, line, Set.of(FileAccess.INPUT));
    }

    /** A sort work file: a SELECT with no OPEN behind it, so no access is recorded. */
    private static FileDefinition sortWorkFile(String name, int line) {
        return file(name, line, Set.of());
    }

    private static FileDefinition file(String name, int line, Set<FileAccess> accesses) {
        return new FileDefinition(name, Optional.of(name), Optional.of("SEQUENTIAL"), accesses,
                new SourcePosition(COBOL, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static CobolSemanticModel program(FileDefinition... files) {
        return new CobolSemanticModel("FIX052", COBOL, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(files));
    }

    private static JclDdStatement dd(String name, int line) {
        return new JclDdStatement(name, Optional.of("FLW." + name), Optional.empty(),
                new SourcePosition(JCL, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(CobolSemanticModel program, JclStep step) {
        JclJobModel job = new JclJobModel("FIX052", JCL, Optional.empty(), List.of(step));
        return CfgFixtures.context(List.of(program), Map.of(), List.of(), List.of(job));
    }

    private static JclStep pgmStep(List<JclDdStatement> dds, Map<String, DatasetAccess> ddRoles) {
        return pgmStep("STEP010", dds, ddRoles);
    }

    private static JclStep pgmStep(String name, List<JclDdStatement> dds,
            Map<String, DatasetAccess> ddRoles) {
        return new JclStep(name, JclExecKind.PGM, "FIX052", Optional.empty(), dds, Map.of(),
                Optional.empty(), Optional.empty(),
                ddRoles.isEmpty() ? Optional.empty()
                        : Optional.of(new JclUtilityFacts(List.of(), List.of(), List.of(),
                                List.of(), ddRoles)),
                new SourcePosition(JCL, 3, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    @Test
    void reportsTheSelectWhoseDdTheStepOmits() {
        List<Finding> findings = new JclMissingDdForProgramRule().evaluate(
                context(program(file("NYUKIN", 17), file("NYULOG", 20), file("STEPLIB", 23)),
                        pgmStep(List.of(dd("NYUKIN", 5)), Map.of())));
        assertEquals(List.of(20), findings.stream().map(f -> f.location().line()).toList(),
                () -> findings.toString());
        assertEquals("R052", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(COBOL, findings.get(0).location().file());
        assertTrue(findings.get(0).message().contains("DD NYULOG"), findings.get(0).message());
    }

    /**
     * The sort work file of a COBOL SORT is an SD the program never opens; the sort serves it
     * through SORTWKnn and the step has no reason to carry a DD of that name.
     */
    @Test
    void ignoresAFileTheProgramNeverOpens() {
        assertEquals(List.of(), new JclMissingDdForProgramRule().evaluate(
                context(program(sortWorkFile("SORTWK", 27)), pgmStep(List.of(), Map.of()))));
    }

    @Test
    void stillReportsAnOpenedFileWhoseDdIsMissing() {
        List<Finding> findings = new JclMissingDdForProgramRule().evaluate(
                context(program(sortWorkFile("SORTWK", 27), file("NYUKIN", 21)),
                        pgmStep(List.of(), Map.of())));
        assertEquals(List.of(21), findings.stream().map(f -> f.location().line()).toList(),
                () -> findings.toString());
    }

    /**
     * The entry is reported once however many steps omit its DD, and the one finding names them
     * all: a reader of the report has the whole work list rather than the first offender.
     */
    @Test
    void namesEveryStepThatOmitsTheSameDd() {
        JclJobModel job = new JclJobModel("FIX052", JCL, Optional.empty(),
                List.of(pgmStep("STEP010", List.of(), Map.of()),
                        pgmStep("STEP020", List.of(), Map.of())));
        List<Finding> findings = new JclMissingDdForProgramRule().evaluate(
                CfgFixtures.context(List.of(program(file("NYULOG", 20))), Map.of(), List.of(),
                        List.of(job)));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message()
                        .contains("FIX052 の STEP010・FIX052 の STEP020 にありません"),
                findings.get(0).message());
    }

    @Test
    void ignoresADdTheStepsOwnControlCardsAllocate() {
        assertEquals(List.of(), new JclMissingDdForProgramRule().evaluate(
                context(program(file("NYULOG", 20)),
                        pgmStep(List.of(), Map.of("NYULOG", DatasetAccess.WRITE)))));
    }
}
