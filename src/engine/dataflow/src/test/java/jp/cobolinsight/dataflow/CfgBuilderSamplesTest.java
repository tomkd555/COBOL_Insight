package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.CfgNodeKind;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** samples/cobol 9本のCFG構築・決定論・到達可能性・PERFORM呼出/復帰の検証。 */
class CfgBuilderSamplesTest {

    static List<String> sampleFiles() {
        return SampleModels.SAMPLE_FILES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleFiles")
    void buildsDeterministicGraphForEachSample(String fileName) {
        CobolSemanticModel model = SampleModels.model(fileName);
        ControlFlowGraph first = CfgBuilder.build(model);
        ControlFlowGraph second = CfgBuilder.build(model);

        assertNotNull(first.entry());
        assertNotNull(first.exit());
        assertEquals(CfgNodeKind.ENTRY, first.entry().kind());
        assertEquals(CfgNodeKind.EXIT, first.exit().kind());
        assertFalse(first.nodes().isEmpty(), fileName + " のCFGが空である");
        assertEquals(first.nodes().size(), second.nodes().size(),
                fileName + " のノード数が非決定的である");
        assertEquals(first.edgeCount(), second.edgeCount(),
                fileName + " の辺数が非決定的である");
    }

    @Test
    void unreachableStatementAfterGoback() {
        // SYK005: 1000-エラーメッセージ編集 の GOBACK(39行) 直後の DISPLAY(40行) は入辺を持たず到達不能。
        ControlFlowGraph cfg = CfgBuilder.build(SampleModels.model("SYK005.cbl"));
        Set<CfgNode> reachable = cfg.reachableNodes();

        CfgNode display40 = statementNodeAtLine(cfg, 40);
        CfgNode display38 = statementNodeAtLine(cfg, 38);
        assertFalse(reachable.contains(display40), "GOBACK直後のDISPLAY(40行)は到達不能であること");
        assertTrue(reachable.contains(display38), "GOBACK前のDISPLAY(38行)は到達可能であること");
    }

    @Test
    void performedParagraphsAreReachable() {
        // SYK004: 0000-メイン処理 が PERFORM する 1000-在庫確認・2000-引当判定 の全ノードが到達可能。
        ControlFlowGraph cfg = CfgBuilder.build(SampleModels.model("SYK004.cbl"));
        Set<CfgNode> reachable = cfg.reachableNodes();

        assertAllNodesReachable(cfg, reachable, "1000-在庫確認");
        assertAllNodesReachable(cfg, reachable, "2000-引当判定");
    }

    private static void assertAllNodesReachable(ControlFlowGraph cfg, Set<CfgNode> reachable,
            String procedureName) {
        List<CfgNode> paragraphNodes = cfg.nodes().stream()
                .filter(node -> node.kind() == CfgNodeKind.STATEMENT)
                .filter(node -> procedureName.equals(node.procedureName()))
                .toList();
        assertFalse(paragraphNodes.isEmpty(), procedureName + " のノードが無い");
        for (CfgNode node : paragraphNodes) {
            assertTrue(reachable.contains(node),
                    procedureName + " のノード " + node + " がPERFORM経由で到達不能である");
        }
    }

    private static CfgNode statementNodeAtLine(ControlFlowGraph cfg, int line) {
        List<CfgNode> matches = cfg.nodes().stream()
                .filter(node -> node.kind() == CfgNodeKind.STATEMENT)
                .filter(node -> node.statement().orElseThrow().range().start().line() == line)
                .toList();
        assertEquals(1, matches.size(), line + "行の文ノードが1つでない: " + matches);
        return matches.get(0);
    }
}
