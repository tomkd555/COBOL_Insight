package jp.cobolinsight.sqlfrontend;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.spi.SqlParser;
import jp.cobolinsight.engineapi.sql.HostVariableBinding;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * engine-api の {@link SqlParser} 実装。{@link SqlStatementAnalyzer} の解析結果
 * (マングリング・文種別・参照テーブル・ホスト変数)を engine-api のSQL文モデルへ変換する。
 */
public final class JsqlSqlParser implements SqlParser {

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    @Override
    public ParseOutcome<SqlStatementModel> parse(EmbeddedBlock sqlBlock) {
        if (sqlBlock.kind() != EmbeddedBlockKind.SQL) {
            throw new IllegalArgumentException("SQLブロックではない: " + sqlBlock.kind());
        }
        SqlBlock block = new SqlBlock(stripExecWrapper(sqlBlock.text()), SqlBlockKind.EXECUTABLE,
                toModulePosition(sqlBlock.range().start()),
                toModulePosition(sqlBlock.range().end()));
        SqlAnalysisResult result = analyzer.analyze(block);
        if (result.status() == AnalysisStatus.NOT_ANALYZABLE) {
            return ParseOutcome.failure(Finding.parseFailure(sqlBlock.range().start(),
                    "SQL文を解析できない: " + result.statusReason()));
        }
        return ParseOutcome.success(new SqlStatementModel(
                toEngineApiKind(result.statementKind()),
                sqlBlock.text(),
                result.mangledSql(),
                toBindings(result.hostVariables()),
                result.tableNames(),
                sqlBlock.range(),
                result.structureSignals()));
    }

    /** 抽出テキストが EXEC SQL 〜 END-EXEC の外形を含む場合、外形を除いてSQL本文だけにする。 */
    private static String stripExecWrapper(String text) {
        return text.replaceFirst("(?is)^\\s*EXEC\\s+SQL\\b", "")
                .replaceFirst("(?is)\\bEND-EXEC\\s*\\.?\\s*$", "")
                .trim();
    }

    private static SourcePosition toModulePosition(
            jp.cobolinsight.engineapi.source.SourcePosition position) {
        return new SourcePosition(position.line(), position.column());
    }

    /** engine-api は SELECT INTO を独立した種別に持たないため、SELECT へまとめる。 */
    private static jp.cobolinsight.engineapi.sql.SqlStatementKind toEngineApiKind(
            SqlStatementKind kind) {
        return switch (kind) {
            case SELECT, SELECT_INTO -> jp.cobolinsight.engineapi.sql.SqlStatementKind.SELECT;
            case INSERT -> jp.cobolinsight.engineapi.sql.SqlStatementKind.INSERT;
            case UPDATE -> jp.cobolinsight.engineapi.sql.SqlStatementKind.UPDATE;
            case DELETE -> jp.cobolinsight.engineapi.sql.SqlStatementKind.DELETE;
            case DECLARE_CURSOR -> jp.cobolinsight.engineapi.sql.SqlStatementKind.DECLARE_CURSOR;
            case OPEN_CURSOR -> jp.cobolinsight.engineapi.sql.SqlStatementKind.OPEN;
            case FETCH -> jp.cobolinsight.engineapi.sql.SqlStatementKind.FETCH;
            case CLOSE_CURSOR -> jp.cobolinsight.engineapi.sql.SqlStatementKind.CLOSE;
            case OTHER -> jp.cobolinsight.engineapi.sql.SqlStatementKind.OTHER;
        };
    }

    private static List<HostVariableBinding> toBindings(List<HostVariableReference> references) {
        List<HostVariableBinding> bindings = new ArrayList<>(references.size());
        for (HostVariableReference reference : references) {
            bindings.add(new HostVariableBinding(reference.dataName(), reference.token(),
                    Optional.ofNullable(reference.indicatorName())));
        }
        return bindings;
    }
}
