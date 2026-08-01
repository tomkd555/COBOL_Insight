package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.callgraph.CallGraphEdge;
import jp.cobolinsight.engineapi.callgraph.CallGraphNode;
import jp.cobolinsight.engineapi.callgraph.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析に失敗した資産が呼出関係グラフへ孤立ノードとして現れることの検証。図から失われると
 * 「これが全体である」と誤読されるため、パースできない資産も種別 UNANALYZABLE のノードとして出す。
 */
class CallGraphUnanalyzableNodeTest {

    private static final String GOOD_SOURCE =
            "       IDENTIFICATION DIVISION.\n"
                    + "       PROGRAM-ID.  GOOD.\n"
                    + "       PROCEDURE DIVISION.\n"
                    + "       0000-MAIN.\n"
                    + "           STOP RUN.\n";

    private static final String BROKEN_SOURCE =
            "       IDENTIFICATION DIVISION.\n"
                    + "       PROGRAM-ID.  BROKEN.\n"
                    + "       PROCEDURE DIVISION.\n"
                    + "       0000-MAIN.\n"
                    + "           MOVE TO .\n";

    @TempDir
    Path tempDir;

    private ScanRunner.Result scanFixture() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.writeString(assets.resolve("cobol/GOOD.cbl"), GOOD_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("cobol/BROKEN.cbl"), BROKEN_SOURCE, StandardCharsets.UTF_8);
        return ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                tempDir.resolve("scan.db"), List.of(), Map.of()));
    }

    private static CallGraphNode unanalyzableNode(ScanRunner.Result result) {
        List<CallGraphNode> nodes = result.callGraph().nodes().stream()
                .filter(node -> node.kind() == NodeKind.UNANALYZABLE)
                .toList();
        assertEquals(1, nodes.size(), "解析不能ノードが1件あること: " + result.callGraph().nodes());
        return nodes.get(0);
    }

    @Test
    void unparsableProgramBecomesUnanalyzableNode() throws IOException {
        ScanRunner.Result result = scanFixture();
        CallGraphNode node = unanalyzableNode(result);

        assertEquals("BROKEN.cbl", node.label(), "ラベルは拡張子付きのファイル名であること");
        assertEquals("cobol/BROKEN.cbl", node.attributes().get("path"), "属性 path は相対パス");
        assertFalse(node.attributes().getOrDefault("reason", "").isBlank(),
                "属性 reason に失敗理由が入ること");

        // 解析できた資産は従来どおりプログラムノードとして現れる。
        assertTrue(result.callGraph().nodes().stream()
                        .anyMatch(n -> n.kind() == NodeKind.PROGRAM && n.label().equals("GOOD")),
                "解析できたプログラムは PROGRAM ノードのままであること");
    }

    @Test
    void unanalyzableNodeIsIsolatedAndUniquelyIdentified() throws IOException {
        ScanRunner.Result result = scanFixture();
        CallGraphNode node = unanalyzableNode(result);

        for (CallGraphEdge edge : result.callGraph().edges()) {
            assertTrue(!edge.fromId().equals(node.id()) && !edge.toId().equals(node.id()),
                    "解析不能ノードへは辺を張らないこと: " + edge);
        }
        assertEquals(1, result.callGraph().nodes().stream()
                .filter(n -> n.id().equals(node.id())).count(), "IDが他のノードと衝突しないこと");
    }

    @Test
    void jsonCarriesKindPathAndReason() throws IOException {
        ScanRunner.Result result = scanFixture();
        String json = result.callGraph().toJson();

        assertTrue(json.contains("\"kind\":\"UNANALYZABLE\""), json);
        assertTrue(json.contains("\"label\":\"BROKEN.cbl\""), json);
        assertTrue(json.contains("\"path\":\"cobol/BROKEN.cbl\""), json);
        assertTrue(json.contains("\"reason\":\""), json);
    }
}
