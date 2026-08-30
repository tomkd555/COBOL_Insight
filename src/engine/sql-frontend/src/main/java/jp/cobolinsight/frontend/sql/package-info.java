/**
 * Analyzes embedded SQL using JSqlParser and implements the SqlParser interface.
 * Before analysis it separates out EXEC SQL blocks and reversibly mangles host variables;
 * the host variables in the analysis result are restored to their original data names from
 * the bidirectional correspondence.
 */
package jp.cobolinsight.frontend.sql;
