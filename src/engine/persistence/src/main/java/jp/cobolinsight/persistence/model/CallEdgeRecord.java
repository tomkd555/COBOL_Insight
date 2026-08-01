package jp.cobolinsight.persistence.model;

/** CALL_EDGE表の1行(呼出関係グラフのエッジ)。resolution・hostVarは省略可。 */
public record CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
        String hostVar) {

    public CallEdgeRecord {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
