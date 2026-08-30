package jp.cobolinsight.frontend.bms;

import java.util.List;

/** A field defined by DFHMDF. name is null for an unlabeled definition. Numeric values are null when unspecified. */
public record BmsField(
        String name,
        int sourceLine,
        Integer posRow,
        Integer posColumn,
        Integer length,
        List<String> attributes) {

    public BmsField {
        attributes = List.copyOf(attributes);
    }
}
