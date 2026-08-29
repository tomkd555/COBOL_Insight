package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GO TO正規化の可約性判定と、不可約領域を複製して単一入口化する処理の検証。 */
class GotoNormalizerTest {

    static List<String> sampleFiles() {
        return SampleModels.SAMPLE_FILES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleFiles")
    void normalizeCompletesAndYieldsReducibleGraph(String fileName) {
        ControlFlowGraph cfg = CfgBuilder.build(SampleModels.model(fileName));
        boolean reducibleBefore = GotoNormalizer.isReducible(cfg);

        ControlFlowGraph normalized = GotoNormalizer.normalize(cfg);
        assertTrue(GotoNormalizer.isReducible(normalized),
                fileName + " の正規化後CFGが不可約である");
        if (reducibleBefore) {
            // 可約なグラフは複製を伴わずそのまま返す(恒等正規化)。
            assertSame(cfg, normalized, fileName + " の可約グラフが恒等正規化されていない");
        } else {
            assertTrue(normalized.nodes().size() >= cfg.nodes().size(),
                    fileName + " の不可約グラフがnode splittingで拡張されていない");
        }
        // 決定論: 同一入力からの正規化2回でノード数が一致。
        assertEquals(normalized.nodes().size(), GotoNormalizer.normalize(cfg).nodes().size(),
                fileName + " の正規化が非決定的である");
    }

    @Test
    void irreducibleRegionIsSingleEntryAfterSplit() {
        ControlFlowGraph irreducible = twoEntryLoop();
        assertFalse(GotoNormalizer.isReducible(irreducible), "2入口ループは不可約であること");

        ControlFlowGraph normalized = GotoNormalizer.normalize(irreducible);
        assertTrue(GotoNormalizer.isReducible(normalized), "正規化後は可約であること");
        assertTrue(normalized.nodes().size() > irreducible.nodes().size(),
                "node splittingでノードが複製されること");

        List<CfgNode> duplicates = normalized.nodes().stream()
                .filter(node -> node.originalNodeId().isPresent())
                .toList();
        assertFalse(duplicates.isEmpty(), "複製ノードが生成されること");

        Map<Integer, CfgNode> originalById = new java.util.HashMap<>();
        for (CfgNode node : irreducible.nodes()) {
            originalById.put(node.id(), node);
        }
        for (CfgNode duplicate : duplicates) {
            CfgNode original = originalById.get(duplicate.originalNodeId().orElseThrow());
            assertTrue(original != null, "複製元idが原グラフのノードを指すこと");
            assertEquals(original.procedureName(), duplicate.procedureName(),
                    "複製ノードは元のprocedureNameを保つこと");
            assertSame(original.statement().orElse(null), duplicate.statement().orElse(null),
                    "複製ノードは元のStatement参照を保つこと");
        }
    }

    /** 2つの流入口を持つループ {B, C}。B・Cとも入口から直接到達し互いに分岐するため不可約。 */
    private static ControlFlowGraph twoEntryLoop() {
        SourceRange rangeB = lineRange(10);
        SourceRange rangeC = lineRange(11);
        Statement stmtB = new SimpleStatement("MOVE", "MOVE WS-A TO WS-B", rangeB);
        Statement stmtC = new SimpleStatement("MOVE", "MOVE WS-C TO WS-D", rangeC);

        CfgNode entry = new CfgNode(0, CfgNodeKind.ENTRY, null, "");
        CfgNode b = new CfgNode(1, CfgNodeKind.STATEMENT, stmtB, "P");
        CfgNode c = new CfgNode(2, CfgNodeKind.STATEMENT, stmtC, "P");
        CfgNode exit = new CfgNode(3, CfgNodeKind.EXIT, null, "");

        Map<CfgNode, List<CfgNode>> successors = new IdentityHashMap<>();
        successors.put(entry, List.of(b, c));
        successors.put(b, List.of(c, exit));
        successors.put(c, List.of(b, exit));
        successors.put(exit, List.of());

        Map<Statement, CfgNode> byStatement = new IdentityHashMap<>();
        byStatement.put(stmtB, b);
        byStatement.put(stmtC, c);

        return new ControlFlowGraph("IRR", List.of(entry, b, c, exit), entry, exit,
                successors, byStatement);
    }

    private static SourceRange lineRange(int line) {
        SourcePosition position = new SourcePosition("irr.cbl", line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        return new SourceRange(position, position);
    }
}
