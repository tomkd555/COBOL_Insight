package jp.cobolinsight.bmsfrontend;

/** 解析エラー。line は1基点、column は0基点。 */
public record BmsParseError(int line, int column, String message) {
}
