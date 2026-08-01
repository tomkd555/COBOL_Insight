package jp.cobolinsight.sqlfrontend;

import jp.cobolinsight.engineapi.sql.SqlStructureSignals;

import java.util.List;

/**
 * EXEC SQL ブロック1件の解析結果。
 *
 * @param status           解析の成否
 * @param statusReason     NOT_ANALYZABLE の理由。ANALYZED では null
 * @param statementKind    SQL文の種別
 * @param cursorName       カーソル関連文(DECLARE/OPEN/FETCH/CLOSE)のカーソル名。それ以外は null
 * @param tableNames       参照テーブル名(スキーマ修飾を含む)
 * @param hostVariables    ホスト変数の対応(原データ名を含む)
 * @param intoTargets      INTO 句のホスト変数の原データ名(SELECT INTO・FETCH)
 * @param mangledSql       マングリング済みSQLテキスト。マングリング不能時は null
 * @param structureSignals SQL指摘(S001〜S006)が読む構文レベルの構造シグナル
 */
public record SqlAnalysisResult(
        AnalysisStatus status,
        String statusReason,
        SqlStatementKind statementKind,
        String cursorName,
        List<String> tableNames,
        List<HostVariableReference> hostVariables,
        List<String> intoTargets,
        String mangledSql,
        SqlStructureSignals structureSignals) {
}
