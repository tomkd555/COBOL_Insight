package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.SourceRecord;
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
            "CALL_EDGE", "FINDING", "JCL_DD", "JCL_STEP", "LINE_MAP", "NODE", "PARAGRAPH",
            "PARAGRAPH_EDGE", "PROGRAM", "SOURCE", "SQL_COLUMN_USE", "SQL_STMT",
            "SQL_TABLE_USE");

    @Test
    void createsAllTablesOnFirstOpen(@TempDir Path dir) throws SQLException {
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

    /**
     * A read-only open must never trigger {@code initializeSchema}: it is the fix for a codepage
     * lookup that used to rebuild the whole project database just because the file predated the
     * current schema version.
     */
    @Test
    void openReadOnlyLeavesAStaleVersionFileUntouched(@TempDir Path dir) throws SQLException {
        Path dbFile = dir.resolve("insight.db");
        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            PersistenceDao dao = new PersistenceDao(db.connection());
            dao.insertSource(new SourceRecord(1L, "/assets", "PROG.cbl", "IBM930", "hash-1", 100L));
            try (Statement st = db.connection().createStatement()) {
                st.execute("PRAGMA user_version = 3");
            }
        }

        try (PersistenceDatabase readOnly = PersistenceDatabase.openReadOnly(dbFile)) {
            assertEquals(EXPECTED_TABLES, tableNames(readOnly));
            assertEquals(3, userVersion(readOnly));
            PersistenceDao dao = new PersistenceDao(readOnly.connection());
            assertTrue(dao.findSource(1L).isPresent(), "既存の行は変わらず読める");
        }

        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            assertEquals(5, userVersion(db), "the version-3 file was left for a plain open to upgrade");
        }
    }

    /** Rebuilding an old file must also drop the tables the current schema no longer creates. */
    @Test
    void rebuildRemovesTablesTheSchemaNoLongerCreates(@TempDir Path dir) throws SQLException {
        Path dbFile = dir.resolve("insight.db");
        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            try (Statement st = db.connection().createStatement()) {
                st.execute("CREATE TABLE ENCODING_INFO (source_id INTEGER REFERENCES SOURCE(id)"
                        + " ON DELETE CASCADE)");
                st.execute("CREATE TABLE BMS_MAPSET (id INTEGER PRIMARY KEY,"
                        + " source_id INTEGER REFERENCES SOURCE(id) ON DELETE CASCADE)");
                st.execute("CREATE TABLE BMS_MAP (id INTEGER PRIMARY KEY,"
                        + " mapset_id INTEGER REFERENCES BMS_MAPSET(id) ON DELETE CASCADE)");
                st.execute("CREATE TABLE BMS_FIELD (map_id INTEGER REFERENCES BMS_MAP(id)"
                        + " ON DELETE CASCADE)");
                st.execute("PRAGMA user_version = 3");
            }
        }

        try (PersistenceDatabase db = PersistenceDatabase.open(dbFile)) {
            assertEquals(EXPECTED_TABLES, tableNames(db));
        }
    }

    private static int userVersion(PersistenceDatabase db) throws SQLException {
        try (Statement st = db.connection().createStatement();
                var rs = st.executeQuery("PRAGMA user_version")) {
            rs.next();
            return rs.getInt(1);
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
