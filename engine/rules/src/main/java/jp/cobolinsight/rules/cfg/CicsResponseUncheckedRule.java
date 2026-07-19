package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.List;

/**
 * R021 CICS応答コード未検査。EXEC CICS コマンドが RESP・RESP2 いずれのオペランドも持たない場合、
 * コマンドの応答コードを検査できないため検出する。RESP/RESP2 を受ける設計であれば、後続の条件で
 * 判定できる。
 */
public final class CicsResponseUncheckedRule implements Rule {

    @Override
    public String id() {
        return "R021";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.HIGH;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (!block.kind().isCics()) {
                    continue;
                }
                if (block.operands().containsKey("RESP") || block.operands().containsKey("RESP2")) {
                    continue;
                }
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "EXEC CICS コマンド(" + cicsVerb(block) + ")が RESP・RESP2 を持たず、"
                                + "応答コードを検査していない。異常終了しても後続処理が継続する。",
                        new SourcePosition(model.sourceFile(), block.range().end().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    private static String cicsVerb(EmbeddedBlock block) {
        String target = block.operands().getOrDefault("MAP",
                block.operands().getOrDefault("PROGRAM", ""));
        String kind = block.kind().name().replace("CICS_", "").replace('_', ' ');
        return target.isBlank() ? kind : kind + " " + target;
    }
}
