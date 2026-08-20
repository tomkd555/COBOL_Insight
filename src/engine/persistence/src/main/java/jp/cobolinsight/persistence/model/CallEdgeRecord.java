package jp.cobolinsight.persistence.model;

/**
 * CALL_EDGE表の1行(呼出関係グラフのエッジ)。resolution・hostVar・lineは省略可。
 * seq は呼出元ノードの出辺のうち何番目かを原本の順序で表す1起点の番号で、順序が分からない辺は0、
 * line は呼出箇所の行で、分からない辺は null とする。
 */
public record CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
        String hostVar, int seq, Integer line) {

    public CallEdgeRecord {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }

    /** 順序も行も持たない辺(コピー句取込辺など、原本の順序を問わない辺)。 */
    public CallEdgeRecord(long id, long fromNode, long toNode, String kind, String resolution,
            String hostVar) {
        this(id, fromNode, toNode, kind, resolution, hostVar, 0, null);
    }
}
