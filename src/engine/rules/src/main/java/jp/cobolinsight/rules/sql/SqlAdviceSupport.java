package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.sql.SqlStatementModel;

/** Common support for SQL-finding rules. Builds a reported location from a SQL statement model's position. */
final class SqlAdviceSupport {

    private SqlAdviceSupport() {
    }

    /** Uses the SQL statement's start line as the reported location. Column is fixed at 1, matching other rules. */
    static SourcePosition location(SqlStatementModel statement) {
        SourcePosition start = statement.range().start();
        return new SourcePosition(start.file(), start.line(), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }
}
