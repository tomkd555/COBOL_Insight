package jp.cobolinsight.frontend.sql;

/**
 * A single EXEC SQL ... END-EXEC block extracted from a COBOL source.
 *
 * @param sqlText the text between EXEC SQL and END-EXEC (each line trimmed and joined with newlines)
 * @param kind    the block kind
 * @param start   the position of the first character of EXEC
 * @param end     the position of the last character of END-EXEC
 */
public record SqlBlock(String sqlText, SqlBlockKind kind, SourcePosition start, SourcePosition end) {
}
