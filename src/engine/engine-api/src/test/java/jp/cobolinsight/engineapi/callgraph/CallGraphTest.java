package jp.cobolinsight.engineapi.callgraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallGraphTest {

    private static CallGraph sampleGraph() {
        CallGraphNode b = new CallGraphNode("PGMB", NodeKind.PROGRAM, "PGMB");
        CallGraphNode a = new CallGraphNode("PGMA", NodeKind.PROGRAM, "PGMA");
        CallGraphNode u = new CallGraphNode("unresolved:WS-NEXT", NodeKind.UNRESOLVED, "WS-NEXT",
                Map.of("variable", "WS-NEXT"));
        CallGraphEdge e2 = new CallGraphEdge("PGMA", "unresolved:WS-NEXT", EdgeKind.CALL, Resolution.UNRESOLVED);
        CallGraphEdge e1 = new CallGraphEdge("PGMA", "PGMB", EdgeKind.CALL, Resolution.CONSTANT);
        return new CallGraph(List.of(b, a, u), List.of(e2, e1));
    }

    // NodeKind と EdgeKind の名前はJSON・DOT出力にそのまま現れる語彙であり、種別の増減は
    // 出力の互換性に影響する。件数を固定して、意図しない追加・削除を検出する。
    @Test
    void nodeKindsCoverAllElevenKinds() {
        assertEquals(11, NodeKind.values().length);
    }

    @Test
    void edgeKindsCoverAllFiveKinds() {
        assertEquals(5, EdgeKind.values().length);
    }

    @Test
    void rejectsDuplicateNodeIds() {
        CallGraphNode a1 = new CallGraphNode("A", NodeKind.PROGRAM, "A");
        CallGraphNode a2 = new CallGraphNode("A", NodeKind.JOB, "A");
        assertThrows(IllegalArgumentException.class, () -> new CallGraph(List.of(a1, a2), List.of()));
    }

    @Test
    void rejectsEdgeReferencingUnknownNode() {
        CallGraphNode a = new CallGraphNode("A", NodeKind.PROGRAM, "A");
        CallGraphEdge e = new CallGraphEdge("A", "MISSING", EdgeKind.CALL, Resolution.CONSTANT);
        assertThrows(IllegalArgumentException.class, () -> new CallGraph(List.of(a), List.of(e)));
    }

    @Test
    void nodesAndEdgesAreSortedDeterministically() {
        CallGraph g = sampleGraph();
        assertEquals(List.of("PGMA", "PGMB", "unresolved:WS-NEXT"),
                g.nodes().stream().map(CallGraphNode::id).toList());
        assertEquals("PGMB", g.edges().get(0).toId());
    }

    @Test
    void equalityIgnoresInsertionOrder() {
        CallGraphNode a = new CallGraphNode("A", NodeKind.PROGRAM, "A");
        CallGraphNode b = new CallGraphNode("B", NodeKind.PROGRAM, "B");
        CallGraphEdge e = new CallGraphEdge("A", "B", EdgeKind.CALL, Resolution.CONSTANT);
        CallGraph g1 = new CallGraph(List.of(a, b), List.of(e));
        CallGraph g2 = new CallGraph(List.of(b, a), List.of(e));
        assertEquals(g1, g2);
        assertEquals(g1.hashCode(), g2.hashCode());
    }

    @Test
    void serializesToDeterministicJson() {
        String expected = "{\"nodes\":["
                + "{\"id\":\"PGMA\",\"kind\":\"PROGRAM\",\"label\":\"PGMA\"},"
                + "{\"id\":\"PGMB\",\"kind\":\"PROGRAM\",\"label\":\"PGMB\"},"
                + "{\"id\":\"unresolved:WS-NEXT\",\"kind\":\"UNRESOLVED\",\"label\":\"WS-NEXT\","
                + "\"attributes\":{\"variable\":\"WS-NEXT\"}}"
                + "],\"edges\":["
                + "{\"from\":\"PGMA\",\"to\":\"PGMB\",\"kind\":\"CALL\",\"resolution\":\"CONSTANT\"},"
                + "{\"from\":\"PGMA\",\"to\":\"unresolved:WS-NEXT\",\"kind\":\"CALL\",\"resolution\":\"UNRESOLVED\"}"
                + "]}";
        assertEquals(expected, sampleGraph().toJson());
    }

    @Test
    void serializesToDeterministicDot() {
        String expected = """
                digraph callgraph {
                  "PGMA" [label="PGMA" kind="PROGRAM"];
                  "PGMB" [label="PGMB" kind="PROGRAM"];
                  "unresolved:WS-NEXT" [label="WS-NEXT" kind="UNRESOLVED" variable="WS-NEXT"];
                  "PGMA" -> "PGMB" [kind="CALL" resolution="CONSTANT"];
                  "PGMA" -> "unresolved:WS-NEXT" [kind="CALL" resolution="UNRESOLVED"];
                }""";
        assertEquals(expected, sampleGraph().toDot());
    }

    @Test
    void dotEscapesQuotesAndBackslashesInLabels() {
        CallGraphNode n = new CallGraphNode("N", NodeKind.DATASET, "A\"B\\C");
        CallGraph g = new CallGraph(List.of(n), List.of());
        String expected = """
                digraph callgraph {
                  "N" [label="A\\"B\\\\C" kind="DATASET"];
                }""";
        assertEquals(expected, g.toDot());
    }

    @Test
    void nodeAttributesAreSortedAndImmutable() {
        CallGraphNode n = new CallGraphNode("N", NodeKind.EXTERNAL_UTILITY, "DFSORT",
                Map.of("z", "1", "a", "2"));
        assertEquals(List.of("a", "z"), List.copyOf(n.attributes().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> n.attributes().put("x", "y"));
    }

    @Test
    void nodeRejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new CallGraphNode(" ", NodeKind.PROGRAM, "X"));
    }
}
