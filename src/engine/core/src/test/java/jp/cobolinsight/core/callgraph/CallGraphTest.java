package jp.cobolinsight.core.callgraph;

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

    // The names of NodeKind and EdgeKind are vocabulary that appears verbatim in the JSON/DOT
    // output, so adding or removing a kind affects output compatibility. Pinning the count
    // detects an unintended addition or removal.
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

    /** An edge's attributes reach both renderings, in key order, and are omitted when empty. */
    @Test
    void edgeAttributesAreSerializedWhenPresent() {
        CallGraphNode step = new CallGraphNode("step:JOB1.STEP010", NodeKind.STEP, "STEP010");
        CallGraphNode dataset = new CallGraphNode("dataset:A.B.C", NodeKind.DATASET, "A.B.C");
        CallGraphEdge edge = new CallGraphEdge("step:JOB1.STEP010", "dataset:A.B.C",
                EdgeKind.REFERENCE, Resolution.CONSTANT, 1, 16, Map.of("access", "READ"));
        CallGraph g = new CallGraph(List.of(step, dataset), List.of(edge));

        assertEquals("{\"from\":\"step:JOB1.STEP010\",\"to\":\"dataset:A.B.C\","
                        + "\"kind\":\"REFERENCE\",\"resolution\":\"CONSTANT\","
                        + "\"seq\":1,\"line\":16,\"attributes\":{\"access\":\"READ\"}}",
                g.toJson().split("\"edges\":\\[")[1].replaceAll("\\]\\}$", ""));
        assertEquals("  \"step:JOB1.STEP010\" -> \"dataset:A.B.C\" "
                        + "[kind=\"REFERENCE\" resolution=\"CONSTANT\" access=\"READ\"];",
                g.toDot().lines().filter(line -> line.contains("->")).findFirst().orElseThrow());
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
