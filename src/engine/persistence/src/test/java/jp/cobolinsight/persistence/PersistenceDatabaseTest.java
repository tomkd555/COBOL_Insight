package jp.cobolinsight.persistence;

import jp.cobolinsight.persistence.model.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDatabaseTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "BMS_FIELD", "BMS_MAP", "BMS_MAPSET", "CALL_EDGE", "ENCODING_INFO",
            "FINDING", "LINE_MAP", "NODE", "PARAGRAPH", "PROGRAM", "SOURCE", "SQL_STMT");

    @Test
    void createsAllTwelveTablesOnFirstOpen(@TempDir Path dir) throws SQLException {
        Path dbFile = dir.resolve("insight.db");
        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            assertEquals(EXPECTED_TABLES, tableNames(db));
        }
    }

    @Test
    void reopeningSameFileIsIdempotentAndKeepsExistingData(@TempDir Path dir) throws SQLException {
        Path dbFile = dir.resolve("insight.db");
        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            PersistenceDao dao = new PersistenceDao(db.connection());
            dao.insertSource(new SourceRecord(1L, "/assets", "PROG.cbl", "IBM930", "hash-1", 100L));
        }

        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            assertEquals(EXPECTED_TABLES, tableNames(db));
            PersistenceDao dao = new PersistenceDao(db.connection());
            assertTrue(dao.findSource(1L).isPresent(), "再オープン後も既存データが残る");
        }
    }

    private static List<String> tableNames(PersistenceDatabase db) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement st = db.connection().createStatement();
                var rs = st.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }
}
