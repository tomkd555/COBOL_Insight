package jp.cobolinsight.app.persistence;

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
                // SQLiteは接続ごとに外部キー制約が既定で無効である。ON DELETE CASCADE を
                // 効かせるため、接続を開くたびに有効化する。
                st.execute("PRAGMA foreign_keys = ON");
            }
            initializeSchema(connection);
            return new PersistenceDatabase(connection);
        } catch (SQLException e) {
            throw new PersistenceException("failed to open database: " + databaseFile, e);
        }
    }

    /**
     * スキーマの版数をSQLiteの user_version に記録し、これが {@link Schema#VERSION} 未満のファイル
     * (新規作成直後は0)にだけDDLを実行する。同版のファイルは既存の表と行をそのまま使う。
     * 版数の古いファイルは表を作り直す。プロジェクトファイルは資産フォルダから導く成果物であり、
     * scan の再実行で全内容を再構築できる。
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
