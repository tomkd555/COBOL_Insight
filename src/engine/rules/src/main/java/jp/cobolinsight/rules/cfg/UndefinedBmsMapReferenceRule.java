package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.AnalysisPhase;
import jp.cobolinsight.core.spi.Rule;
import jp.cobolinsight.core.spi.RuleDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * R031 未定義BMSマップ参照。EXEC CICS SEND/RECEIVE MAP が参照する MAP・MAPSET を BMS マップ
 * モデルと突合し、マップセットが存在しない、または該当マップがマップセットに定義されていない
 * 参照を検出する。定義の無いマップを参照すると、画面の送受信が実行時に失敗する。
 */
public final class UndefinedBmsMapReferenceRule implements Rule {

    private static final Pattern MAP_OPERAND = Pattern.compile("(?i)\\bMAP\\s*\\(");

    @Override
    public String id() {
        return "R031";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("存在しないBMSマップ・フィールドの参照", "CICS")
                .summary("BMS のマップ定義に無いマップセット・マップを参照する"
                        + "EXEC CICS SEND/RECEIVE MAP を検出します。")
                .rationale("定義の無いマップを指す送受信は実行時に失敗し、"
                        + "画面が表示されないまま異常終了します。")
                .detection("SEND MAP・RECEIVE MAP の MAP・MAPSET を BMS マップモデルと"
                        + "突き合わせ、マップセットが存在しない、"
                        + "またはマップがそのマップセットに定義されていないものを検出します。")
                .remedy("マップ名・マップセット名の綴りを BMS 定義と揃えるか、"
                        + "不足しているマップを BMS へ定義します。")
                .example("""
                        EXEC CICS SEND MAP('MAPXX') MAPSET('MAPSET1') END-EXEC.
                        """, """
                        EXEC CICS SEND MAP('MAP01') MAPSET('MAPSET1') END-EXEC.
                        """)
                .build();
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
        List<BmsMapset> mapsets = context.bmsMapsets();
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() != EmbeddedBlockKind.CICS_SEND_MAP
                        && block.kind() != EmbeddedBlockKind.CICS_RECEIVE_MAP) {
                    continue;
                }
                String map = block.operands().get("MAP");
                String mapset = block.operands().get("MAPSET");
                if (map == null) {
                    continue;
                }
                if (isDefined(mapsets, map, mapset)) {
                    continue;
                }
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                        "参照するマップ " + map
                                + (mapset == null ? "" : "(マップセット " + mapset + ")")
                                + " は BMS マップ定義に存在しない。",
                        new SourcePosition(model.sourceFile(), mapOperandLine(block), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
        return findings;
    }

    private static boolean isDefined(List<BmsMapset> mapsets, String map, String mapset) {
        for (BmsMapset candidate : mapsets) {
            if (mapset != null && !candidate.name().equalsIgnoreCase(mapset)) {
                continue;
            }
            for (BmsMap bmsMap : candidate.maps()) {
                if (bmsMap.name().equalsIgnoreCase(map)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** MAP オペランドが最初に現れる行。ブロック先頭(EXEC CICS 行)とは異なるため走査する。 */
    private static int mapOperandLine(EmbeddedBlock block) {
        int startLine = block.range().start().line();
        List<String> lines = block.text().lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            if (MAP_OPERAND.matcher(lines.get(i)).find()) {
                return startLine + i;
            }
        }
        return startLine;
    }
}
