package jp.cobolinsight.persistence.model;

/**
 * NODE表の1行(呼出関係グラフのノード)。ソースに対応するノードは NODE.id = SOURCE.id で登録し、
 * ソースに対応しないノード(ジョブステップ・データセット等)はそれより大きいIDへ採番する。
 */
public record NodeRecord(long id, String type, String label) {

    public NodeRecord {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
