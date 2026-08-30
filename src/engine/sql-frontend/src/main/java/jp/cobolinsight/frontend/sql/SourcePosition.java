package jp.cobolinsight.frontend.sql;

/** A position in the original source, expressed as a 1-based line and column. */
public record SourcePosition(int line, int column) {
}
