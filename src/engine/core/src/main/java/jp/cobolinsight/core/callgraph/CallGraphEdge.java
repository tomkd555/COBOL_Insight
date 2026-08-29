package jp.cobolinsight.core.callgraph;

import java.util.Objects;

/**
 * 呼出関係グラフのエッジ。ノードIDで両端を参照し、解決根拠を保持する。seq は呼出元ノードの
 * 出辺のうち何番目かを原本の順序(JCLのステップ順・文の出現順)で表す1起点の番号で、順序が
 * 分からない辺では0とする。line は呼出元ソースの呼出箇所の行で、分からない場合は null とする。
 *
 * <p>辺の同一性は両端・種別・解決根拠だけで決める。同じ呼出先を複数箇所から呼んでも1本の辺へ
 * 畳むためであり、seq・line は最初の出現のものを記録として持つ。
 */
public record CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution,
        int seq, Integer line) {

    public CallGraphEdge {
        if (fromId == null || fromId.isBlank()) {
            throw new IllegalArgumentException("fromId must not be blank");
        }
        if (toId == null || toId.isBlank()) {
            throw new IllegalArgumentException("toId must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(resolution, "resolution");
    }

    /** 順序も行も分からない辺。 */
    public CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution) {
        this(fromId, toId, kind, resolution, 0, null);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CallGraphEdge other
                && fromId.equals(other.fromId)
                && toId.equals(other.toId)
                && kind == other.kind
                && resolution == other.resolution;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromId, toId, kind, resolution);
    }
}
