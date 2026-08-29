package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
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
 * Verification that an asset that failed to analyze still appears as an isolated node in the
 * call graph. If it were missing from the diagram, it could be misread as "this is the whole
 * picture," so an asset that cannot be parsed is still emitted as a node of kind UNANALYZABLE.
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

    private ScanOutcome scanFixture() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.writeString(assets.resolve("cobol/GOOD.cbl"), GOOD_SOURCE, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("cobol/BROKEN.cbl"), BROKEN_SOURCE, StandardCharsets.UTF_8);
        return Pipelines.scan(assets,
                databaseFile(), List.of(), Map.of());
    }

    private static CallGraphNode unanalyzableNode(ScanOutcome result) {
        List<CallGraphNode> nodes = result.callGraph().nodes().stream()
                .filter(node -> node.kind() == NodeKind.UNANALYZABLE)
                .toList();
        assertEquals(1, nodes.size(), "解析不能ノードが1件あること: " + result.callGraph().nodes());
        return nodes.get(0);
    }

    @Test
    void unparsableProgramBecomesUnanalyzableNode() throws IOException {
        ScanOutcome result = scanFixture();
        CallGraphNode node = unanalyzableNode(result);

        assertEquals("BROKEN.cbl", node.label(), "ラベルは拡張子付きのファイル名であること");
        assertEquals("cobol/BROKEN.cbl", node.attributes().get("path"), "属性 path は相対パス");
        assertFalse(node.attributes().getOrDefault("reason", "").isBlank(),
                "属性 reason に失敗理由が入ること");

        // An asset that was successfully analyzed still appears as a program node, as before.
        assertTrue(result.callGraph().nodes().stream()
                        .anyMatch(n -> n.kind() == NodeKind.PROGRAM && n.label().equals("GOOD")),
                "解析できたプログラムは PROGRAM ノードのままであること");
    }

    @Test
    void unanalyzableNodeIsIsolatedAndUniquelyIdentified() throws IOException {
        ScanOutcome result = scanFixture();
        CallGraphNode node = unanalyzableNode(result);

        for (CallGraphEdge edge : result.callGraph().edges()) {
            assertTrue(!edge.fromId().equals(node.id()) && !edge.toId().equals(node.id()),
                    "解析不能ノードへは辺を張らないこと: " + edge);
        }
        assertEquals(1, result.callGraph().nodes().stream()
                .filter(n -> n.id().equals(node.id())).count(), "IDが他のノードと衝突しないこと");
    }

    /**
     * The NODE row for an asset that could not be analyzed must also stay as kind UNANALYZABLE.
     * If it were left as the kind the scan assigns first (PROGRAM), a reader of the project file
     * could not distinguish it from an asset that was successfully analyzed.
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
        ScanOutcome result = scanFixture();
        String json = result.callGraph().toJson();

        assertTrue(json.contains("\"kind\":\"UNANALYZABLE\""), json);
        assertTrue(json.contains("\"label\":\"BROKEN.cbl\""), json);
        assertTrue(json.contains("\"path\":\"cobol/BROKEN.cbl\""), json);
        assertTrue(json.contains("\"reason\":\""), json);
    }
}
