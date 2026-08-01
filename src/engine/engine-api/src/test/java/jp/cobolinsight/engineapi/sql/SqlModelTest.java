package jp.cobolinsight.engineapi.sql;

import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlModelTest {

    private static SourceRange range() {
        SourcePosition p = new SourcePosition("A.cbl", 100, 12, -1);
        return new SourceRange(p, p);
    }

    @Test
    void hostVariableBindingKeepsOriginalAndMangledNames() {
        HostVariableBinding binding = new HostVariableBinding("WS-CUST-ID", "WS_CUST_ID",
                Optional.of("WS-CUST-IND"));
        assertEquals("WS-CUST-ID", binding.originalName());
        assertEquals("WS_CUST_ID", binding.mangledName());
        assertEquals("WS-CUST-IND", binding.indicatorName().orElseThrow());
    }

    @Test
    void hostVariableBindingRejectsBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new HostVariableBinding(" ", "X", Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new HostVariableBinding("X", " ", Optional.empty()));
    }

    @Test
    void statementModelHoldsBothTextsAndReferencedTables() {
        SqlStatementModel stmt = new SqlStatementModel(SqlStatementKind.SELECT,
                "SELECT C1 FROM T1 WHERE ID = :WS-CUST-ID",
                "SELECT C1 FROM T1 WHERE ID = :WS_CUST_ID",
                List.of(new HostVariableBinding("WS-CUST-ID", "WS_CUST_ID", Optional.empty())),
                List.of("T1"), range(), SqlStructureSignals.empty());
        assertEquals(SqlStatementKind.SELECT, stmt.kind());
        assertTrue(stmt.originalText().contains("WS-CUST-ID"));
        assertEquals(List.of("T1"), stmt.referencedTables());
        assertThrows(UnsupportedOperationException.class, () -> stmt.hostVariables().clear());
    }

    @Test
    void statementModelRejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new SqlStatementModel(SqlStatementKind.OTHER,
                " ", "X", List.of(), List.of(), range(), SqlStructureSignals.empty()));
    }

    @Test
    void statementModelRejectsNullStructureSignals() {
        assertThrows(NullPointerException.class, () -> new SqlStatementModel(SqlStatementKind.SELECT,
                "SELECT 1 FROM T", "SELECT 1 FROM T", List.of(), List.of("T"), range(), null));
    }

    @Test
    void emptyStructureSignalsHaveNoSignals() {
        SqlStructureSignals signals = SqlStructureSignals.empty();
        assertFalse(signals.selectStar());
        assertTrue(signals.nonSargablePredicates().isEmpty());
        assertTrue(signals.functionOnColumnPredicates().isEmpty());
        assertTrue(signals.cursor().isEmpty());
        assertFalse(signals.hasFetchFirst());
        assertFalse(signals.hasOptimizeFor());
        assertFalse(signals.hasWithUr());
    }

    @Test
    void structureSignalsCopyTheirLists() {
        List<String> mutable = new ArrayList<>(List.of("SUBSTR(COL, 1, 3) = 'abc'"));
        SqlStructureSignals signals = new SqlStructureSignals(false, mutable, List.of(),
                Optional.empty(), false, false, false);
        mutable.clear();
        assertEquals(1, signals.nonSargablePredicates().size());
        assertThrows(UnsupportedOperationException.class,
                () -> signals.nonSargablePredicates().clear());
    }

    @Test
    void cursorSignalsCarryForClauseInformation() {
        CursorSignals cursor = new CursorSignals("SYKZAIKOCUR", false, false, true,
                List.of("ZAIKO_SU", "HIKIATE_SU"));
        assertEquals("SYKZAIKOCUR", cursor.cursorName());
        assertTrue(cursor.forUpdate());
        assertEquals(List.of("ZAIKO_SU", "HIKIATE_SU"), cursor.forUpdateColumns());
        assertThrows(UnsupportedOperationException.class, () -> cursor.forUpdateColumns().clear());
    }
}
