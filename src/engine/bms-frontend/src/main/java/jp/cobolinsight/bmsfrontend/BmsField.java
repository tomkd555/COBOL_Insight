package jp.cobolinsight.bmsfrontend;

import java.util.List;

/** DFHMDF が定義するフィールド。name はラベル無し定義のとき null。数値は未指定のとき null。 */
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
