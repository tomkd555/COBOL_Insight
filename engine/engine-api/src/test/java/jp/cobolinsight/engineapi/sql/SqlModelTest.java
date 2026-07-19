package jp.cobolinsight.engineapi.sql;

import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                List.of("T1"), range());
        assertEquals(SqlStatementKind.SELECT, stmt.kind());
        assertTrue(stmt.originalText().contains("WS-CUST-ID"));
        assertEquals(List.of("T1"), stmt.referencedTables());
        assertThrows(UnsupportedOperationException.class, () -> stmt.hostVariables().clear());
    }

    @Test
    void statementModelRejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new SqlStatementModel(SqlStatementKind.OTHER,
                " ", "X", List.of(), List.of(), range()));
    }
}
