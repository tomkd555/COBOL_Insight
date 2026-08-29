package jp.cobolinsight.frontend.sql;

/**
 * Db2 固有句の検出1件。
 *
 * @param clause      検出した句
 * @param offset      SQLテキスト先頭からの0始まり文字オフセット
 * @param matchedText 一致したテキスト
 */
public record Db2ClauseFinding(Db2Clause clause, int offset, String matchedText) {
}
