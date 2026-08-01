package jp.cobolinsight.engineapi.callgraph;

import java.util.Objects;

/** 呼出関係グラフのエッジ。ノードIDで両端を参照し、解決根拠を保持する。 */
public record CallGraphEdge(String fromId, String toId, EdgeKind kind, Resolution resolution) {

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
}
