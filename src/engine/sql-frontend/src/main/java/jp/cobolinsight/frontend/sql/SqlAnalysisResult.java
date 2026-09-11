package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;

import java.util.List;

/**
 * Analysis result for a single EXEC SQL block. facts holds everything the analysis read off the
 * statement itself — its kind, its tables and the extracted facts — and {@link Db2zSqlParser}
 * turns it into the model once it can supply the text and the position.
 *
 * @param analysis         FULL when the grammar accepted the statement, DEGRADED when only a
 *                         keyword scan could be applied
 * @param diagnostic       what stopped the full analysis; null for FULL
 * @param mangledSql       the mangled SQL text
 * @param hostVariables    host variable correspondences (including original data names)
 * @param structureSignals syntax-level structural signals read by SQL findings (S001-S006);
 *                         always empty for DEGRADED
 * @param facts            the statement kind, the referenced tables and the extracted facts
 */
public record SqlAnalysisResult(
        SqlAnalysis analysis,
        String diagnostic,
        String mangledSql,
        List<HostVariableReference> hostVariables,
        SqlStructureSignals structureSignals,
        SqlStatementModel.Builder facts) {

    /** The kind of SQL statement. */
    public SqlStatementKind statementKind() {
        return facts.kind();
    }

    /** The cursor a cursor-related or positioned statement names; null when it names none. */
    public String cursorName() {
        return facts.cursorName().orElse(null);
    }

    /** Referenced table names, schema qualification included. */
    public List<String> tableNames() {
        return facts.referencedTables();
    }

    /** Original data names of the host variables in the INTO list. */
    public List<String> intoTargets() {
        return facts.intoTargets();
    }
}
