package jp.cobolinsight.app.persistence;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Holds a connection to a SQLite database file and creates the schema on first open. */
public final class PersistenceDatabase implements AutoCloseable {

    private final Connection connection;

    private PersistenceDatabase(Connection connection) {
        this.connection = connection;
    }

    public static PersistenceDatabase open(Path databaseFile) {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement st = connection.createStatement()) {
                // SQLite disables foreign key constraints by default on every connection. To make
                // ON DELETE CASCADE take effect, enable it each time a connection is opened.
                st.execute("PRAGMA foreign_keys = ON");
            }
            initializeSchema(connection);
            return new PersistenceDatabase(connection);
        } catch (SQLException e) {
            throw new PersistenceException("failed to open database: " + databaseFile, e);
        }
    }

    /**
     * Records the schema version in SQLite's user_version, and runs the DDL only on files where
     * this is below {@link Schema#VERSION} (0 immediately after a fresh file is created). Files
     * already at the current version keep their existing tables and rows as they are. Files at an
     * older version have their tables recreated. The project file is a derived artifact built
     * from the asset folder, and its entire content can be rebuilt by re-running scan.
     */
    private static void initializeSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                rs.next();
                version = rs.getInt(1);
            }
            if (version < Schema.VERSION) {
                for (String ddl : Schema.DROP_STATEMENTS) {
                    st.execute(ddl);
                }
                for (String ddl : Schema.CREATE_STATEMENTS) {
                    st.execute(ddl);
                }
                st.execute("PRAGMA user_version = " + Schema.VERSION);
            }
        }
    }

    /**
     * Opens the database strictly for reading: the file is opened with SQLite's read-only open
     * flag, so {@link #initializeSchema} never runs and {@code user_version} and every table are
     * left exactly as they are. A lookup that finds no matching row in a database at an older
     * schema version simply reports none, the same as an empty database would.
     */
    public static PersistenceDatabase openReadOnly(Path databaseFile) {
        try {
            SQLiteConfig config = new SQLiteConfig();
            config.setReadOnly(true);
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile,
                    config.toProperties());
            return new PersistenceDatabase(connection);
        } catch (SQLException e) {
            throw new PersistenceException("failed to open database read-only: " + databaseFile, e);
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new PersistenceException("failed to close database", e);
        }
    }
}
