package jp.cobolinsight.core.callgraph;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 呼出関係グラフのノード。attributes は種別固有の付帯情報(未解決ノードの変数名、
 * 外部ユーティリティの種別タグなど)をキー昇順で保持する。
 */
public record CallGraphNode(String id, NodeKind kind, String label, Map<String, String> attributes) {

    public CallGraphNode {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        attributes = Collections.unmodifiableSortedMap(new TreeMap<>(attributes));
    }

    public CallGraphNode(String id, NodeKind kind, String label) {
        this(id, kind, label, Map.of());
    }
}
