package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.spi.SqlParser;
import jp.cobolinsight.core.sql.HostVariableBinding;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@link SqlParser} implementation for engine-api. Converts the analysis results of
 * {@link SqlStatementAnalyzer} (mangling, statement kind, referenced tables, host variables and
 * the extracted facts) into engine-api's SQL statement model. Every block yields a model: a
 * statement the grammar would not take comes back degraded, carrying its diagnostic, never as a
 * failure.
 */
public final class Db2zSqlParser implements SqlParser {

    /** The wrapper a COBOL program writes around its SQL, which is no part of the statement. */
    private static final Pattern EXEC_SQL = Pattern.compile("(?is)^\\s*EXEC\\s+SQL\\b");
    private static final Pattern END_EXEC = Pattern.compile("(?is)\\bEND-EXEC\\s*\\.?\\s*$");

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();

    @Override
    public ParseOutcome<SqlStatementModel> parse(EmbeddedBlock sqlBlock) {
        if (sqlBlock.kind() != EmbeddedBlockKind.SQL) {
            throw new IllegalArgumentException("SQLブロックではありません: " + sqlBlock.kind());
        }
        SqlBlock block = new SqlBlock(stripExecWrapper(sqlBlock.text()), SqlBlockKind.EXECUTABLE,
                toModulePosition(sqlBlock.range().start()),
                toModulePosition(sqlBlock.range().end()));
        SqlAnalysisResult result = analyzer.analyze(block);
        return ParseOutcome.success(result.facts().build(
                sqlBlock.text(),
                result.mangledSql(),
                toBindings(result.hostVariables()),
                sqlBlock.range(),
                result.structureSignals(),
                result.analysis(),
                Optional.ofNullable(result.diagnostic())));
    }

    /** If the extracted text includes the EXEC SQL ... END-EXEC wrapper, strip it to leave only the SQL body. */
    private static String stripExecWrapper(String text) {
        return END_EXEC.matcher(EXEC_SQL.matcher(text).replaceFirst(""))
                .replaceFirst("")
                .trim();
    }

    private static SourcePosition toModulePosition(
            jp.cobolinsight.core.source.SourcePosition position) {
        return new SourcePosition(position.line(), position.column());
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
