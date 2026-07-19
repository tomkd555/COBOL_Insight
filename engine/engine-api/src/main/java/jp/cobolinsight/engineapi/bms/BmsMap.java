package jp.cobolinsight.engineapi.bms;

import java.util.List;
import java.util.Optional;

/** DFHMDIが定義する画面。名称・大きさと、含むフィールドの集合を保持する。 */
public record BmsMap(String name, int sizeRows, int sizeCols, List<BmsField> fields) {

    public BmsMap {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (sizeRows < 1) {
            throw new IllegalArgumentException("sizeRows must be >= 1: " + sizeRows);
        }
        if (sizeCols < 1) {
            throw new IllegalArgumentException("sizeCols must be >= 1: " + sizeCols);
        }
        fields = List.copyOf(fields);
    }

    /** 名前が一致する最初のフィールドを返す。R031(存在しないフィールド参照)の突合に使う。 */
    public Optional<BmsField> field(String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName)).findFirst();
    }
}
