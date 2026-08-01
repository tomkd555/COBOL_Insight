package jp.cobolinsight.sqlfrontend;

/**
 * COBOLソースから抽出した EXEC SQL 〜 END-EXEC ブロック1件。
 *
 * @param sqlText EXEC SQL と END-EXEC の間のテキスト(行ごとにトリムし改行で連結)
 * @param kind    ブロック種別
 * @param start   EXEC の先頭文字の位置
 * @param end     END-EXEC の末尾文字の位置
 */
public record SqlBlock(String sqlText, SqlBlockKind kind, SourcePosition start, SourcePosition end) {
}
