package jp.cobolinsight.core.sql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 埋め込みSQL文の構文レベルの構造シグナル。SQL指摘ルール(S001〜S006)が真偽値・リストで読む。
 * sql-frontend が JSqlParser の構文木と Db2 固有句の正規表現から算出し、SqlStatementModel へ載せる。
 *
 * @param selectStar                 SELECT 句に * が出現するか(S001)
 * @param nonSargablePredicates      WHERE 左辺が列を関数・演算式で包む、または先頭 % の LIKE の該当箇所(S002)
 * @param functionOnColumnPredicates 比較の片側が列引数の関数呼出しまたは CAST(col AS ..) の該当箇所(S003)
 * @param cursor                     DECLARE CURSOR の場合のカーソル情報。それ以外は空(S004)
 * @param hasFetchFirst              FETCH FIRST n ROWS ONLY 句の有無(S005)
 * @param hasOptimizeFor             OPTIMIZE FOR n ROWS 句の有無(S006)
 * @param hasWithUr                  WITH UR 句の有無(参考)
 */
public record SqlStructureSignals(boolean selectStar, List<String> nonSargablePredicates,
        List<String> functionOnColumnPredicates, Optional<CursorSignals> cursor,
        boolean hasFetchFirst, boolean hasOptimizeFor, boolean hasWithUr) {

    public SqlStructureSignals {
        nonSargablePredicates = List.copyOf(nonSargablePredicates);
        functionOnColumnPredicates = List.copyOf(functionOnColumnPredicates);
        Objects.requireNonNull(cursor, "cursor");
    }

    /** シグナルが1つも立たない空の構造。SELECT系以外の文や、構造検査の対象外に用いる。 */
    public static SqlStructureSignals empty() {
        return new SqlStructureSignals(false, List.of(), List.of(), Optional.empty(),
                false, false, false);
    }
}
