package jp.cobolinsight.frontend.bms;

/** A parse error. line is 1-based, column is 0-based. */
public record BmsParseError(int line, int column, String message) {
}
