package jp.cobolinsight.rules.sql;

import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

/** SQL指摘ルール共通の補助。SQL文モデルの位置から報告位置を組む。 */
final class SqlAdviceSupport {

    private SqlAdviceSupport() {
    }

    /** SQL文の開始行を報告位置とする。桁は他ルールに合わせ1固定。 */
    static SourcePosition location(SqlStatementModel statement) {
        SourcePosition start = statement.range().start();
        return new SourcePosition(start.file(), start.line(), 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
    }
}
