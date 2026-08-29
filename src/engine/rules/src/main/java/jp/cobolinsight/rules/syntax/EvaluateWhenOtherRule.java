package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R013 EVALUATE文のWHEN OTHER欠如。EVALUATE文にWHEN OTHER句が指定されていない箇所を検出する。
 * WHEN OTHER句が無いと、いずれのWHEN句にも一致しない値が何の処理も受けずに通過する。
 * 意味モデル上、EVALUATE文は分岐(BRANCH)の複合文であり、先頭ブロックのラベルが "THEN" である
 * IF文と区別する。WHEN OTHER句はラベル "OTHER" のブロックとして現れる。
 */
public final class EvaluateWhenOtherRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R013", "EVALUATE文のWHEN OTHER欠如", "制御フロー")
            .summary("WHEN OTHER 句を持たない EVALUATE 文を検出します。")
            .rationale("どの WHEN にも一致しない値が何の処理も受けずに素通りするため、"
                    + "想定外の入力が記録も通知もされないまま無視されます。")
            .detection("分岐の複合文のうち EVALUATE 文を対象とし、"
                    + "ラベル OTHER のブロックを持たないものを検出します。IF 文は対象外とします。")
            .remedy("WHEN OTHER 句を置き、想定外の値に対する処理(異常扱い・既定値の設定)を書きます。")
            .example("""
                    EVALUATE WS-KBN
                        WHEN "1" PERFORM SHINKI-SHORI
                        WHEN "2" PERFORM HENKO-SHORI
                    END-EVALUATE.
                    """, """
                    EVALUATE WS-KBN
                        WHEN "1" PERFORM SHINKI-SHORI
                        WHEN "2" PERFORM HENKO-SHORI
                        WHEN OTHER PERFORM ERROR-SHORI
                    END-EVALUATE.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            Statements.walk(model, statement -> {
                if (!(statement instanceof CompoundStatement compound)
                        || compound.kind() != ControlKind.BRANCH || isIf(compound)) {
                    return;
                }
                boolean hasOther = compound.blocks().stream()
                        .anyMatch(block -> "OTHER".equals(block.label()));
                if (!hasOther) {
                    findings.add(Finding.of("R013", Severity.MEDIUM.toLevel(),
                            "EVALUATE文にWHEN OTHER句が指定されておらず、いずれのWHEN句にも"
                                    + "一致しない値の場合に処理が素通りする。",
                            compound.range().start()));
                }
            });
        }
        return findings;
    }

    private static boolean isIf(CompoundStatement compound) {
        List<StatementBlock> blocks = compound.blocks();
        return !blocks.isEmpty() && "THEN".equals(blocks.get(0).label());
    }
}
