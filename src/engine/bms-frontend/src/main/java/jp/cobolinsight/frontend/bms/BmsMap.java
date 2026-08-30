package jp.cobolinsight.frontend.bms;

import java.util.List;

/** A map defined by DFHMDI. Numeric values are null when unspecified. */
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
