package jp.cobolinsight.core.sql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The syntax-level structural signals of an embedded SQL statement. Read as booleans and lists
 * by the SQL finding rules (S001, S002, S004). Computed by sql-frontend from the JSqlParser
 * syntax tree and regular expressions over Db2-specific clauses, and carried on SqlStatementModel.
 *
 * @param selectStar                 whether * appears in the SELECT clause (S001)
 * @param nonSargablePredicates      locations where the WHERE clause's left side wraps a column
 *                                   in a function or arithmetic expression, or a LIKE with a
 *                                   leading % (S002)
 * @param functionOnColumnPredicates locations where one side of a comparison is a function call
 *                                   taking a column argument, or CAST(col AS ..) (S002)
 * @param cursor                     the cursor information for a DECLARE CURSOR; empty otherwise (S004)
 * @param hasFetchFirst              whether a FETCH FIRST n ROWS ONLY clause is present (informational)
 * @param hasOptimizeFor             whether an OPTIMIZE FOR n ROWS clause is present (informational)
 * @param hasWithUr                  whether a WITH UR clause is present (informational)
 */
public record SqlStructureSignals(boolean selectStar, List<String> nonSargablePredicates,
        List<String> functionOnColumnPredicates, Optional<CursorSignals> cursor,
        boolean hasFetchFirst, boolean hasOptimizeFor, boolean hasWithUr) {

    public SqlStructureSignals {
        nonSargablePredicates = List.copyOf(nonSargablePredicates);
        functionOnColumnPredicates = List.copyOf(functionOnColumnPredicates);
        Objects.requireNonNull(cursor, "cursor");
    }

    /** An empty structure with no signals set. Used for non-SELECT statements and cases outside the scope of structural checks. */
    public static SqlStructureSignals empty() {
        return new SqlStructureSignals(false, List.of(), List.of(), Optional.empty(),
                false, false, false);
    }
}
