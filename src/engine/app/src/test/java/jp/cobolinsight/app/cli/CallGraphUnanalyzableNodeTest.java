package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.NodeRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    private Path databaseFile() {
        return tempDir.resolve("scan.db");
    }

    private ScanRunner.Result scanFixture() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.writeString(assets.resolve("cobol/GOOD.cbl"), GOOD_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("cobol/BROKEN.cbl"), BROKEN_SOURCE, StandardCharsets.UTF_8);
        return ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                databaseFile(), List.of(), Map.of()));
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

    /**
     * 解析できなかった資産の NODE 行も種別 UNANALYZABLE で残ること。走査が先に付ける種別
     * (PROGRAM)のままでは、プロジェクトファイルを読む側が解析できた資産と区別できない。
     */
    @Test
    void unanalyzableNodeIsPersistedWithItsKind() throws IOException {
        scanFixture();

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile())) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            Map<String, Long> sourceIdByPath = dao.findAllSources().stream()
                    .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));

            NodeRecord broken = dao.findNode(sourceIdByPath.get("cobol/BROKEN.cbl")).orElseThrow();
            assertEquals("UNANALYZABLE", broken.type(), "パースできない資産の行の種別");
            assertEquals("BROKEN.cbl", broken.label());

            NodeRecord good = dao.findNode(sourceIdByPath.get("cobol/GOOD.cbl")).orElseThrow();
            assertEquals("PROGRAM", good.type(), "解析できた資産は PROGRAM のままであること");
        }
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
