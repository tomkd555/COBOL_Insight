package jp.cobolinsight.frontend.bms;

import java.util.List;

/** DFHMDI が定義するマップ。数値は未指定のとき null。 */
public record BmsMap(
        String name,
        int sourceLine,
        Integer sizeRows,
        Integer sizeColumns,
        Integer positionLine,
        Integer positionColumn,
        List<BmsField> fields) {

    public BmsMap {
        fields = List.copyOf(fields);
    }
}
