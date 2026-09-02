package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.SourceTextIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * R015 Item length/boundary mismatch from REDEFINES. Detects a configuration where the total byte
 * length of the items redefined by REDEFINES exceeds the byte length of the original item. If the
 * redefining item is larger than the original, it overwrites the adjacent storage. Byte length is
 * accumulated by the shared resolver from PICTURE, USAGE, OCCURS, and descendant group items; a
 * pair for which either length cannot be resolved is not judged.
 */
public final class RedefinesMismatchRule implements Rule {

    private static final RuleMeta META =
            RuleMeta.named("R015", "REDEFINES による項目長の不一致", "データ定義")
                    .summary("REDEFINES で再定義した項目群の総バイト長が"
                            + "元の項目より大きい構成を検出します。")
                    .rationale("再定義側への書き込みが元の項目の領域を越え、隣接する項目を壊します。")
                    .detection("PICTURE・USAGE・OCCURS と配下の集団項目からバイト長を積算し、"
                            + "再定義側が元の項目を超えるものを検出します。"
                            + "いずれかの長さを解決できない対は対象外です。")
                    .remedy("再定義側の長さを元の項目以内に収めるか、"
                            + "元の項目を必要な長さまで広げてください。")
                    .example("""
                            01  WS-AREA      PIC X(10).
                            01  WS-AREA-R    REDEFINES WS-AREA.
                                05  WS-PART1 PIC X(8).
                                05  WS-PART2 PIC X(8).
                            """, """
                            01  WS-AREA      PIC X(16).
                            01  WS-AREA-R    REDEFINES WS-AREA.
                                05  WS-PART1 PIC X(8).
                                05  WS-PART2 PIC X(8).
                            """)
                    .severity(Severity.HIGH)
                    .commands(Command.LINT, Command.REPORT)
                    .targets(AssetKind.COBOL)
                    .needs(Needs.SEMANTIC, Needs.SOURCE_TEXT)
                    .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        SourceTextIndex texts = context.artifact(SourceTextIndex.class).orElse(null);
        if (texts == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            DataFlowSupport support = new DataFlowSupport(model, texts);
            List<DataItem> all = new ArrayList<>();
            for (DataItem item : model.dataItems()) {
                collect(item, all);
            }
            for (DataItem redefiner : all) {
                if (redefiner.redefines().isEmpty()) {
                    continue;
                }
                DataItem original = support.item(redefiner.redefines().get()).orElse(null);
                if (original == null) {
                    continue;
                }
                Integer redefLen = support.byteLength(redefiner).orElse(null);
                Integer origLen = support.byteLength(original).orElse(null);
                if (redefLen != null && origLen != null && redefLen > origLen) {
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            redefiner.name() + "（" + redefLen + "バイト）が元の項目 "
                                    + original.name() + "（" + origLen
                                    + "バイト）を超えています。隣接する領域を上書きします。",
                            new SourcePosition(model.sourceFile(), redefiner.position().line(), 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                }
            }
        }
        return findings;
    }

    private static void collect(DataItem item, List<DataItem> all) {
        all.add(item);
        for (DataItem child : item.children()) {
            collect(child, all);
        }
    }
}
