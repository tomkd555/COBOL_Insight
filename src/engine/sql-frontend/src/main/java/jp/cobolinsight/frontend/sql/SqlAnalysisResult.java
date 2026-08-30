package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlStructureSignals;

import java.util.List;

/**
 * Analysis result for a single EXEC SQL block.
 *
 * @param status           the outcome of analysis
 * @param statusReason     the reason for NOT_ANALYZABLE; null for ANALYZED
 * @param statementKind    the kind of SQL statement
 * @param cursorName       the cursor name for a cursor-related statement (DECLARE/OPEN/FETCH/CLOSE); null otherwise
 * @param tableNames       referenced table names (including schema qualification)
 * @param hostVariables    host variable correspondences (including original data names)
 * @param intoTargets      original data names of host variables in the INTO clause (SELECT INTO, FETCH)
 * @param mangledSql       the mangled SQL text; null when mangling was not possible
 * @param structureSignals syntax-level structural signals read by SQL findings (S001-S006)
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
