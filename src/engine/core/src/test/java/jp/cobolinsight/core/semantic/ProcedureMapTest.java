package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Which statement and procedure each PERFORM relation belongs to, told apart by identity. */
class ProcedureMapTest {

    /**
     * A COPY member included twice in one paragraph gives two statements of one range and two
     * equal relations; each statement takes one of them.
     */
    @Test
    void twoEqualRelationsGoToTwoStatementsOfOneRange() {
        SourceRange copied = range("ERR.cpy", 1);
        SimpleStatement first = perform(copied);
        SimpleStatement second = perform(copied);
        PerformRelation one = new PerformRelation("P1", "LOG", Optional.empty(), copied);
        PerformRelation other = new PerformRelation("P1", "LOG", Optional.empty(), copied);
        ProcedureMap map = new ProcedureMap(model(List.of(
                paragraph("P1", null, first, second), paragraph("LOG", null)),
                List.of(one, other)));

        assertEquals(1, map.performsOf(first).size());
        assertEquals(1, map.performsOf(second).size());
        assertSame(one, map.performsOf(first).get(0));
        assertSame(other, map.performsOf(second).get(0));
        assertEquals(List.of(0, 0), List.of(map.procedureOf(one), map.procedureOf(other)));
    }

    /** Two procedures of one name: the relation goes to the one whose statement holds it. */
    @Test
    void ofTwoSameNamedProceduresTheOneWhoseStatementHoldsTheRelationTakesIt() {
        SimpleStatement inA = perform(range("PGM.cbl", 5));
        SimpleStatement inB = perform(range("PGM.cbl", 9));
        PerformRelation relation =
                new PerformRelation("P1", "X", Optional.empty(), range("PGM.cbl", 9));
        ProcedureMap map = new ProcedureMap(model(List.of(
                section("SECT-A"), paragraph("P1", "SECT-A", inA),
                section("SECT-B"), paragraph("P1", "SECT-B", inB)), List.of(relation)));

        assertEquals(List.of(), map.performsOf(inA));
        assertEquals(List.of(relation), map.performsOf(inB));
        assertEquals(3, map.procedureOf(relation));
        assertEquals(Optional.of("SECT-B"), map.sectionOf(relation));
    }

    /** A SORT holds its INPUT and its OUTPUT PROCEDURE, two relations of its own range. */
    @Test
    void aSortHoldsItsInputAndOutputProcedure() {
        SourceRange sortRange = range("PGM.cbl", 5);
        SimpleStatement sort = new SimpleStatement("SORT", "SORT F1 ...", sortRange);
        PerformRelation input = new PerformRelation("P1", "IN-PROC", Optional.empty(), sortRange);
        PerformRelation output =
                new PerformRelation("P1", "OUT-PROC", Optional.empty(), sortRange);
        ProcedureMap map = new ProcedureMap(model(List.of(paragraph("P1", null, sort)),
                List.of(input, output)));

        assertEquals(List.of(input, output), map.performsOf(sort));
        assertEquals(List.of(input, output), map.performsIn(0));
    }

    /** A relation no statement's range holds belongs to no statement and no procedure. */
    @Test
    void aRelationNoStatementHoldsBelongsToNothing() {
        SimpleStatement statement = perform(range("PGM.cbl", 5));
        PerformRelation stray =
                new PerformRelation("P1", "X", Optional.empty(), range("PGM.cbl", 7));
        ProcedureMap map = new ProcedureMap(model(List.of(paragraph("P1", "S", statement)),
                List.of(stray)));

        assertEquals(List.of(), map.performsOf(statement));
        assertEquals(-1, map.procedureOf(stray));
        assertEquals(Optional.empty(), map.sectionOf(stray));
    }

    private static CobolSemanticModel model(List<Procedure> procedures,
            List<PerformRelation> performs) {
        return new CobolSemanticModel("PGM", "PGM.cbl", List.of(), procedures, List.of(),
                performs, List.of(), List.of(), List.of());
    }

    private static Procedure paragraph(String name, String section, Statement... statements) {
        return new Procedure(name, ProcedureKind.PARAGRAPH, Optional.ofNullable(section),
                List.of(statements), range("PGM.cbl", 1));
    }

    private static Procedure section(String name) {
        return new Procedure(name, ProcedureKind.SECTION, Optional.empty(), List.of(),
                range("PGM.cbl", 1));
    }

    private static SimpleStatement perform(SourceRange range) {
        return new SimpleStatement("PERFORM", "PERFORM X", range);
    }

    private static SourceRange range(String file, int line) {
        SourcePosition position = new SourcePosition(file, line, 12,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(position, position);
    }
}
