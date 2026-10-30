package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The method each procedure becomes, and the methods a PERFORM calls. */
class MethodNamesTest {

    /**
     * A qualified name already taken gets {@code _2} and on; a shared name outside any section
     * has no section to qualify it and falls back to its plain name.
     */
    @Test
    void aTakenNameGetsANumberAndANameOutsideSectionsFallsBack() {
        MethodNames names = new MethodNames(model(List.of(
                paragraph("X", null), paragraph("X", null),
                section("A-SECT"), paragraph("COMMON-EXIT", "A-SECT"),
                paragraph("COMMON-EXIT", "A-SECT"), paragraph("COMMON-EXIT-IN-A-SECT", "A-SECT")),
                List.of()));

        assertEquals(List.of("X", "X_2", "A_SECT", "COMMON_EXIT_IN_A_SECT_2",
                        "COMMON_EXIT_IN_A_SECT_3", "COMMON_EXIT_IN_A_SECT"),
                List.of(names.of(0), names.of(1), names.of(2), names.of(3), names.of(4),
                        names.of(5)));
    }

    /**
     * A THRU end that resolves to no procedure, or to one ahead of the target, leaves the
     * target's own method; a PERFORM with no relation, or an unresolved target, has none.
     */
    @Test
    void aThruEndUnresolvedOrAheadLeavesTheTargetsOwnMethod() {
        List<Statement> statements = new ArrayList<>();
        List<PerformRelation> performs = new ArrayList<>();
        String[][] written = {{"B", "C"}, {"B", "NO-SUCH"}, {"C", "B"}, {"NO-SUCH", null}};
        for (int i = 0; i < written.length; i++) {
            SourceRange range = range(10 + i);
            statements.add(new SimpleStatement("PERFORM", "PERFORM", range));
            performs.add(new PerformRelation("A", written[i][0],
                    Optional.ofNullable(written[i][1]), range));
        }
        SimpleStatement noRelation = new SimpleStatement("PERFORM", "PERFORM B", range(20));
        statements.add(noRelation);
        Procedure a = new Procedure("A", ProcedureKind.PARAGRAPH, Optional.empty(), statements,
                range(9));
        MethodNames names = new MethodNames(model(List.of(a, paragraph("B", null),
                paragraph("C", null)), performs));

        assertEquals(Optional.of(List.of("B", "C")), names.performed(perform(statements, 0)));
        assertEquals(Optional.of(List.of("B")), names.performed(perform(statements, 1)));
        assertEquals(Optional.of(List.of("C")), names.performed(perform(statements, 2)));
        assertEquals(Optional.empty(), names.performed(perform(statements, 3)));
        assertEquals(Optional.empty(), names.performed(noRelation));
    }

    private static SimpleStatement perform(List<Statement> statements, int index) {
        return (SimpleStatement) statements.get(index);
    }

    private static CobolSemanticModel model(List<Procedure> procedures,
            List<PerformRelation> performs) {
        return new CobolSemanticModel("PGM", "PGM.cbl", List.of(), procedures, List.of(),
                performs, List.of(), List.of(), List.of());
    }

    private static Procedure paragraph(String name, String section) {
        return new Procedure(name, ProcedureKind.PARAGRAPH, Optional.ofNullable(section),
                List.of(), range(1));
    }

    private static Procedure section(String name) {
        return new Procedure(name, ProcedureKind.SECTION, Optional.empty(), List.of(), range(1));
    }

    private static SourceRange range(int line) {
        SourcePosition position =
                new SourcePosition("PGM.cbl", line, 12, SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(position, position);
    }
}
