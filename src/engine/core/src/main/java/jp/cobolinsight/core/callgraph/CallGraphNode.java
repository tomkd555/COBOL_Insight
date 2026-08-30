package jp.cobolinsight.core.callgraph;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A node of the call graph. attributes holds kind-specific auxiliary information (such as the
 * variable name of an unresolved node, or the kind tag of an external utility) in ascending key order.
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
