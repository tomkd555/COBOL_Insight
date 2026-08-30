package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the robustness of the fixed-point analysis against the 9 samples/cobol files, and
 * verifies against a real sample the reachability of uninitialized values that grounds R001
 * (reference to an uninitialized variable).
 */
class DataFlowEngineSamplesTest {

    static List<String> sampleFiles() {
        return SampleModels.SAMPLE_FILES;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleFiles")
    void analyzesEachSampleDeterministically(String fileName) {
        CobolSemanticModel model = SampleModels.model(fileName);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow first = DataFlowEngine.analyze(model, cfg);
        ProgramDataFlow second = DataFlowEngine.analyze(model, cfg);

        assertNotNull(first);
        for (CfgNode node : cfg.nodes()) {
            assertEquals(first.liveOut(node), second.liveOut(node),
                    fileName + " の liveOut が非決定的である: " + node);
            assertEquals(first.defsAt(node), second.defsAt(node),
                    fileName + " の defsAt が非決定的である: " + node);
        }
    }

    @Test
    void detectsUninitializedStockOnGuardedMoveInSyk004() {
        // SYK004: the MOVE 999 TO WS-在庫残数 in 1000-在庫確認 exists only inside the
        // IF LK-商品コード NOT = SPACES guard. On the path where the guard is false, WS-在庫残数
        // stays undefined and reaches the IF WS-在庫残数 >= LK-要求数量 (line 41) in 2000-引当判定.
        // This is the situation R001 detects.
        CobolSemanticModel model = SampleModels.model("SYK004.cbl");
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);

        CfgNode condition = cfg.nodes().stream()
                .filter(node -> node.kind() == CfgNodeKind.STATEMENT)
                .filter(node -> node.statement().orElseThrow() instanceof CompoundStatement)
                .filter(node -> ((CompoundStatement) node.statement().orElseThrow())
                        .conditionText().contains("WS-在庫残数"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("WS-在庫残数 を含む条件ノードが無い"));

        assertTrue(df.mayReachUninitialized(condition, "WS-在庫残数"),
                "ガード偽の経路で WS-在庫残数 が未初期化のまま到達し得る");
    }
}
