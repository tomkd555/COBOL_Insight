package jp.cobolinsight.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLiteデータベースファイルへの接続を保持し、初回オープン時にスキーマを作成する。 */
public final class PersistenceDatabase implements AutoCloseable {

    private final Connection connection;

    private PersistenceDatabase(Connection connection) {
        this.connection = connection;
    }

    public static PersistenceDatabase open(Path databaseFile) {
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
            }
            initializeSchema(connection);
            return new PersistenceDatabase(connection);
        } catch (SQLException e) {
            throw new PersistenceException("failed to open database: " + databaseFile, e);
        }
    }

    private static void initializeSchema(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                rs.next();
                version = rs.getInt(1);
            }
            if (version < Schema.VERSION) {
                for (String ddl : Schema.CREATE_STATEMENTS) {
                    st.execute(ddl);
                }
                st.execute("PRAGMA user_version = " + Schema.VERSION);
            }
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
