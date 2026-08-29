/**
 * JSqlParserによる埋め込みSQL解析を行い、SqlParser インターフェースを実装する。
 * 解析の前に EXEC SQL ブロックの分離とホスト変数の可逆マングリングを行い、
 * 解析結果のホスト変数は双方向の対応から原データ名へ復元する。
 */
package jp.cobolinsight.frontend.sql;
