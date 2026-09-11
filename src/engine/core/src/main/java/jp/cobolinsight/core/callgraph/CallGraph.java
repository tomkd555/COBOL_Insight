package jp.cobolinsight.core.callgraph;

import jp.cobolinsight.core.json.JsonWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A single model of the call graph. Call-relationship analysis results are aggregated into this type.
 * Nodes and edges are normalized and held in ID order, and serialization to JSON/DOT returns
 * deterministic output that does not depend on insertion order.
 */
public final class CallGraph {

    private static final Comparator<CallGraphEdge> EDGE_ORDER =
            Comparator.comparing(CallGraphEdge::fromId)
                    .thenComparing(CallGraphEdge::toId)
                    .thenComparing(edge -> edge.kind().name())
                    .thenComparing(edge -> edge.resolution().name());

    private final List<CallGraphNode> nodes;
    private final List<CallGraphEdge> edges;

    public CallGraph(Collection<CallGraphNode> nodes, Collection<CallGraphEdge> edges) {
        List<CallGraphNode> sortedNodes = new ArrayList<>(nodes);
        sortedNodes.sort(Comparator.comparing(CallGraphNode::id));
        Set<String> ids = new HashSet<>();
        for (CallGraphNode node : sortedNodes) {
            if (!ids.add(node.id())) {
                throw new IllegalArgumentException("duplicate node id: " + node.id());
            }
        }
        List<CallGraphEdge> sortedEdges = new ArrayList<>(edges);
        sortedEdges.sort(EDGE_ORDER);
        for (CallGraphEdge edge : sortedEdges) {
            if (!ids.contains(edge.fromId())) {
                throw new IllegalArgumentException("edge references unknown node: " + edge.fromId());
            }
            if (!ids.contains(edge.toId())) {
                throw new IllegalArgumentException("edge references unknown node: " + edge.toId());
            }
        }
        this.nodes = List.copyOf(sortedNodes);
        this.edges = List.copyOf(sortedEdges);
    }

    /** List of nodes (ascending by ID). */
    public List<CallGraphNode> nodes() {
        return nodes;
    }

    /** List of edges (ascending by fromId, toId, kind, resolution). */
    public List<CallGraphEdge> edges() {
        return edges;
    }

    /**
     * JSON representation. For a node or an edge with empty attributes, the attributes key itself
     * is omitted. An edge's seq and line are likewise emitted only when known (an edge with unknown
     * order omits seq, and one with an unknown line omits line).
     */
    public String toJson() {
        JsonWriter w = new JsonWriter();
        w.beginObject().name("nodes").beginArray();
        for (CallGraphNode node : nodes) {
            w.beginObject()
                    .name("id").value(node.id())
                    .name("kind").value(node.kind().name())
                    .name("label").value(node.label());
            if (!node.attributes().isEmpty()) {
                w.name("attributes").beginObject();
                for (Map.Entry<String, String> entry : node.attributes().entrySet()) {
                    w.name(entry.getKey()).value(entry.getValue());
                }
                w.endObject();
            }
            w.endObject();
        }
        w.endArray().name("edges").beginArray();
        for (CallGraphEdge edge : edges) {
            w.beginObject()
                    .name("from").value(edge.fromId())
                    .name("to").value(edge.toId())
                    .name("kind").value(edge.kind().name())
                    .name("resolution").value(edge.resolution().name());
            if (edge.seq() > 0) {
                w.name("seq").value(edge.seq());
            }
            if (edge.line() != null) {
                w.name("line").value(edge.line());
            }
            if (!edge.attributes().isEmpty()) {
                w.name("attributes").beginObject();
                for (Map.Entry<String, String> entry : edge.attributes().entrySet()) {
                    w.name(entry.getKey()).value(entry.getValue());
                }
                w.endObject();
            }
            w.endObject();
        }
        w.endArray().endObject();
        return w.toString();
    }

    public String toDot() {
        StringBuilder sb = new StringBuilder("digraph callgraph {\n");
        for (CallGraphNode node : nodes) {
            sb.append("  \"").append(dotEscape(node.id()))
                    .append("\" [label=\"").append(dotEscape(node.label()))
                    .append("\" kind=\"").append(node.kind().name()).append('"');
            for (Map.Entry<String, String> entry : node.attributes().entrySet()) {
                sb.append(' ').append(entry.getKey())
                        .append("=\"").append(dotEscape(entry.getValue())).append('"');
            }
            sb.append("];\n");
        }
        for (CallGraphEdge edge : edges) {
            sb.append("  \"").append(dotEscape(edge.fromId()))
                    .append("\" -> \"").append(dotEscape(edge.toId()))
                    .append("\" [kind=\"").append(edge.kind().name())
                    .append("\" resolution=\"").append(edge.resolution().name())
                    .append('"');
            for (Map.Entry<String, String> entry : edge.attributes().entrySet()) {
                sb.append(' ').append(entry.getKey())
                        .append("=\"").append(dotEscape(entry.getValue())).append('"');
            }
            sb.append("];\n");
        }
        sb.append('}');
        return sb.toString();
    }

    private static String dotEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CallGraph other
                && nodes.equals(other.nodes)
                && edges.equals(other.edges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodes, edges);
    }

    @Override
    public String toString() {
        return "CallGraph[nodes=" + nodes.size() + ", edges=" + edges.size() + "]";
    }
}
