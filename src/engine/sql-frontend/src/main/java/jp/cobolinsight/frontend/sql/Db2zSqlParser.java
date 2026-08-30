package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.HostVariableBinding;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@link SqlParser} implementation for engine-api. Converts the analysis results of
 * {@link SqlStatementAnalyzer} (mangling, statement kind, referenced tables, host variables)
 * into engine-api's SQL statement model.
 */
public final class Db2zSqlParser implements SqlParser {

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

    /** If the extracted text includes the EXEC SQL ... END-EXEC wrapper, strip it to leave only the SQL body. */
    private static String stripExecWrapper(String text) {
        return text.replaceFirst("(?is)^\\s*EXEC\\s+SQL\\b", "")
                .replaceFirst("(?is)\\bEND-EXEC\\s*\\.?\\s*$", "")
                .trim();
    }

    private static SourcePosition toModulePosition(
            jp.cobolinsight.core.source.SourcePosition position) {
        return new SourcePosition(position.line(), position.column());
    }

    /** engine-api has no separate kind for SELECT INTO, so fold it into SELECT. */
    private static jp.cobolinsight.core.sql.SqlStatementKind toEngineApiKind(
            SqlStatementKind kind) {
        return switch (kind) {
            case SELECT, SELECT_INTO -> jp.cobolinsight.core.sql.SqlStatementKind.SELECT;
            case INSERT -> jp.cobolinsight.core.sql.SqlStatementKind.INSERT;
            case UPDATE -> jp.cobolinsight.core.sql.SqlStatementKind.UPDATE;
            case DELETE -> jp.cobolinsight.core.sql.SqlStatementKind.DELETE;
            case DECLARE_CURSOR -> jp.cobolinsight.core.sql.SqlStatementKind.DECLARE_CURSOR;
            case OPEN_CURSOR -> jp.cobolinsight.core.sql.SqlStatementKind.OPEN;
            case FETCH -> jp.cobolinsight.core.sql.SqlStatementKind.FETCH;
            case CLOSE_CURSOR -> jp.cobolinsight.core.sql.SqlStatementKind.CLOSE;
            case OTHER -> jp.cobolinsight.core.sql.SqlStatementKind.OTHER;
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
