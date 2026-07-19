package jp.cobolinsight.persistence.model;

/** NODE表の1行(呼出関係グラフのノード)。 */
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
