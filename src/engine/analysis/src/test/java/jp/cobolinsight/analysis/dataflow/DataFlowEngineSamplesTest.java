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
 * samples/cobol 9本に対する不動点解析の頑健性と、R001(未初期化変数の参照)の判定根拠となる
 * 未初期化到達を実サンプルで検証する。
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
        // SYK004: 1000-在庫確認 の MOVE 999 TO WS-在庫残数 は IF LK-商品コード NOT = SPACES の
        // ガード内のみ。ガード偽の経路では WS-在庫残数 が未定義のまま 2000-引当判定 の
        // IF WS-在庫残数 >= LK-要求数量(41行)へ到達する。R001 が検出する状況である。
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
