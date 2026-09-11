/**
 * Analyzes embedded SQL with the MAPA Db2 for z/OS grammar and implements the SqlParser interface.
 * Before analysis it strips the EXEC SQL wrapper and reversibly mangles what the grammar's
 * identifier token will not take — host variables and Japanese names — and every name in the
 * analysis result is restored from the bidirectional correspondence.
 *
 * <p>A statement the grammar will not take in full is analysed in degraded form rather than
 * thrown away: its kind, its tables and its cursor name come from a keyword scan, and the
 * diagnostic says what stopped the parse.</p>
 */
package jp.cobolinsight.frontend.sql;
