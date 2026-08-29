package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.CopyExpansionEntry;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticModelTest {

    private static SourcePosition pos(int line) {
        return new SourcePosition("A.cbl", line, 8, -1);
    }

    private static SourceRange range(int line) {
        return new SourceRange(pos(line), pos(line));
    }

    @Test
    void dataItemAcceptsStandardAndSpecialLevels() {
        DataItem elementary = new DataItem(5, "WS-AMOUNT", Optional.of("S9(7)V99"), Optional.of("COMP-3"),
                Optional.of("100"), Optional.empty(), Optional.empty(), List.of(), List.of(), pos(10));
        assertEquals(5, elementary.level());
        assertEquals(Optional.of("100"), elementary.value());
        // レベル77は他の項目に従属しない独立項目を表し、1〜49と同じく項目として受け付ける
        DataItem level77 = new DataItem(77, "WS-FLAG", Optional.of("X"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), pos(11));
        assertEquals(77, level77.level());
    }

    @Test
    void dataItemRejectsInvalidLevel() {
        assertThrows(IllegalArgumentException.class, () -> new DataItem(0, "X", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), pos(1)));
        // レベル88の条件名は ConditionName が持つため、DataItem のレベルとしては受け付けない
        assertThrows(IllegalArgumentException.class, () -> new DataItem(88, "X", Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), pos(1)));
    }

    @Test
    void dataItemHoldsRedefinesOccursAndConditionNames() {
        ConditionName cond = new ConditionName("WS-OK", List.of("'0'"), pos(21));
        DataItem item = new DataItem(1, "WS-REC", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("WS-OTHER"), Optional.of(new Occurs(1, 10, Optional.of("WS-CNT"))),
                List.of(cond), List.of(), pos(20));
        assertEquals("WS-OTHER", item.redefines().orElseThrow());
        assertEquals(10, item.occurs().orElseThrow().maxTimes());
        assertEquals("WS-OK", item.conditionNames().get(0).name());
    }

    @Test
    void occursRejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Occurs(-1, 5, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Occurs(6, 5, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Occurs(0, 0, Optional.empty()));
    }

    @Test
    void conditionNameRequiresValues() {
        assertThrows(IllegalArgumentException.class, () -> new ConditionName("WS-OK", List.of(), pos(1)));
    }

    @Test
    void dataItemChildrenAreImmutable() {
        DataItem child = new DataItem(5, "WS-A", Optional.of("X"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), pos(2));
        DataItem parent = new DataItem(1, "WS-GROUP", Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(child), pos(1));
        assertThrows(UnsupportedOperationException.class, () -> parent.children().add(child));
    }

    @Test
    void simpleStatementHoldsVerbAndText() {
        SimpleStatement s = new SimpleStatement("MOVE", "MOVE A TO B", range(30));
        assertEquals("MOVE", s.verb());
        assertEquals(30, s.range().start().line());
    }

    @Test
    void compoundStatementRequiresBlocks() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompoundStatement(ControlKind.BRANCH, "A = B", List.of(), range(1)));
        StatementBlock thenBlock = new StatementBlock("THEN",
                List.of(new SimpleStatement("MOVE", "MOVE 1 TO X", range(2))));
        StatementBlock elseBlock = new StatementBlock("ELSE", List.of());
        CompoundStatement branch = new CompoundStatement(ControlKind.BRANCH, "A = B",
                List.of(thenBlock, elseBlock), range(1));
        assertEquals(2, branch.blocks().size());
    }

    @Test
    void goToStatementAllowsEmptyTargetsForAlteredGoTo() {
        GoToStatement altered = new GoToStatement(List.of(), Optional.empty(), range(5));
        assertTrue(altered.targets().isEmpty());
        GoToStatement depending = new GoToStatement(List.of("P1", "P2"), Optional.of("WS-IDX"), range(6));
        assertEquals("WS-IDX", depending.dependingOn().orElseThrow());
    }

    @Test
    void procedureHoldsKindAndStatements() {
        Procedure p = new Procedure("MAIN-RTN", ProcedureKind.PARAGRAPH, Optional.of("MAIN-SEC"),
                List.of(new SimpleStatement("DISPLAY", "DISPLAY 'X'", range(40))), range(40));
        assertEquals(ProcedureKind.PARAGRAPH, p.kind());
        assertEquals("MAIN-SEC", p.sectionName().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> p.statements().clear());
    }

    @Test
    void callRelationDistinguishesStaticAndDynamic() {
        CallRelation stat = new CallRelation("PGMA", CallKind.STATIC, "PGMB", range(50));
        CallRelation dyn = new CallRelation("PGMA", CallKind.DYNAMIC, "WS-PGM-NAME", range(51));
        assertEquals(CallKind.STATIC, stat.kind());
        assertEquals("WS-PGM-NAME", dyn.target());
    }

    @Test
    void performRelationHoldsOptionalThru() {
        PerformRelation p = new PerformRelation("MAIN-RTN", "SUB-RTN", Optional.of("SUB-EXIT"), range(60));
        assertEquals("SUB-EXIT", p.thruProcedure().orElseThrow());
    }

    @Test
    void embeddedBlockKindsCoverSqlAndSixCicsCommands() {
        assertEquals(7, EmbeddedBlockKind.values().length);
        assertTrue(EmbeddedBlockKind.CICS_SEND_MAP.isCics());
        assertTrue(!EmbeddedBlockKind.SQL.isCics());
    }

    @Test
    void embeddedBlockOperandsAreSortedAndImmutable() {
        Map<String, String> operands = new LinkedHashMap<>();
        operands.put("MAPSET", "SYKMSET");
        operands.put("MAP", "SYKMAP");
        EmbeddedBlock block = new EmbeddedBlock(EmbeddedBlockKind.CICS_SEND_MAP,
                "EXEC CICS SEND MAP('SYKMAP') MAPSET('SYKMSET') END-EXEC", operands, range(70));
        assertEquals(List.of("MAP", "MAPSET"), List.copyOf(block.operands().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> block.operands().put("X", "Y"));
    }

    @Test
    void cobolSemanticModelAggregatesAllComponents() {
        CobolSemanticModel model = new CobolSemanticModel("PGMA", "A.cbl",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new CopyExpansionEntry(10, 20, "CPYA.cpy", 1)), List.of());
        assertEquals("PGMA", model.programId());
        assertEquals("CPYA.cpy", model.copyExpansions().get(0).copybookPath());
        assertThrows(UnsupportedOperationException.class, () -> model.dataItems().clear());
    }

    @Test
    void cobolSemanticModelRejectsBlankProgramId() {
        assertThrows(IllegalArgumentException.class, () -> new CobolSemanticModel(" ", "A.cbl",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }
}
