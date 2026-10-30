package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The rule the control flow graph and the stored paragraph flow resolve procedure names by. */
class ProcedureNamesTest {

    /** MAIN-SECT, then P1 in SECT-A and in SECT-B, then COMMON outside any section. */
    private static final ProcedureNames NAMES = new ProcedureNames(List.of(
            procedure("MAIN-SECT", ProcedureKind.SECTION, null),
            procedure("SECT-A", ProcedureKind.SECTION, null),
            procedure("P1", ProcedureKind.PARAGRAPH, "SECT-A"),
            procedure("SECT-B", ProcedureKind.SECTION, null),
            procedure("P1", ProcedureKind.PARAGRAPH, "SECT-B")));

    @Test
    void anUnqualifiedNameMeansTheParagraphOfTheSectionItIsWrittenIn() {
        assertEquals(2, NAMES.indexOf("P1", Optional.empty(), Optional.of("SECT-A")));
        assertEquals(4, NAMES.indexOf("p1", Optional.empty(), Optional.of("sect-b")));
    }

    @Test
    void fromAnotherSectionAnUnqualifiedNameMeansTheFirstDefinition() {
        assertEquals(2, NAMES.indexOf("P1", Optional.empty(), Optional.of("MAIN-SECT")));
        assertEquals(2, NAMES.indexOf("P1", Optional.empty(), Optional.empty()));
    }

    @Test
    void aQualifiedNameMeansTheParagraphOfTheNamedSection() {
        assertEquals(4, NAMES.indexOf("P1", Optional.of("SECT-B"), Optional.of("SECT-A")));
        assertEquals(2, NAMES.indexOf("P1", Optional.of("SECT-A"), Optional.of("SECT-B")));
        assertEquals(2, NAMES.indexOf("P1", Optional.of("NO-SUCH"), Optional.of("MAIN-SECT")),
                "a qualifier no section answers to is read as if it were absent");
    }

    @Test
    void aSectionNameMeansTheSectionAndAnUnknownNameNothing() {
        assertEquals(3, NAMES.indexOf("SECT-B", Optional.empty(), Optional.of("SECT-A")));
        assertEquals(-1, NAMES.indexOf("P9", Optional.empty(), Optional.of("SECT-A")));
    }

    @Test
    void theRangeOfAPerformRunsFromItsTargetToTheLastProcedureOfItsEnd() {
        assertEquals(Optional.of(new ProcedureNames.Range(1, 2)),
                NAMES.rangeOf(perform("SECT-A", null), Optional.empty()),
                "a section runs through its paragraphs");
        assertEquals(Optional.of(new ProcedureNames.Range(2, 4)),
                NAMES.rangeOf(perform("P1", "SECT-B"), Optional.of("SECT-A")),
                "the target resolves from the PERFORM's own section");
        assertEquals(Optional.of(new ProcedureNames.Range(4, 4)),
                NAMES.rangeOf(perform("P1", "P1"), Optional.of("SECT-B")));
        assertEquals(Optional.of(new ProcedureNames.Range(1, 4)),
                NAMES.rangeOf(perform("SECT-A", "SECT-B"), Optional.empty()));
        assertEquals(Optional.empty(), NAMES.rangeOf(perform("P9", "SECT-B"), Optional.empty()));
    }

    @Test
    void aThruEndUnresolvedOrAheadOfTheTargetGivesTheTargetAlone() {
        assertEquals(Optional.of(new ProcedureNames.Range(3, 4)),
                NAMES.rangeOf(perform("SECT-B", "P9"), Optional.empty()));
        assertEquals(Optional.of(new ProcedureNames.Range(3, 4)),
                NAMES.rangeOf(perform("SECT-B", "SECT-A"), Optional.empty()));
        assertEquals(Optional.of(new ProcedureNames.Range(4, 4)),
                NAMES.rangeOf(perform("P1", "MAIN-SECT"), Optional.of("SECT-B")));
    }

    /** A PERFORM of {@code target}, THRU {@code thru} where that is given. */
    private static PerformRelation perform(String target, String thru) {
        SourcePosition position = SourcePosition.fileStart("PGM.cbl");
        return new PerformRelation("MAIN-SECT", target, Optional.ofNullable(thru),
                new SourceRange(position, position));
    }

    private static Procedure procedure(String name, ProcedureKind kind, String section) {
        SourcePosition position = SourcePosition.fileStart("PGM.cbl");
        return new Procedure(name, kind, Optional.ofNullable(section), List.of(),
                new SourceRange(position, position));
    }
}
