package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;

/**
 * R010 Use of the ALTER statement. The ALTER statement rewrites a GO TO's destination at
 * runtime, making the control flow impossible to trace statically. Detects every ALTER
 * statement in the procedure division.
 */
public final class AlterStatementRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R010", "静的に追えない ALTER 文", "制御フロー")
            .summary("手続き部の ALTER 文をすべて検出します。")
            .rationale("ALTER 文は GO TO 文の移行先を実行時に書き換えるため、"
                    + "原始プログラムを読んでも制御の流れを追えず、静的解析も移行先を"
                    + "決められません。")
            .detection("手続き部に現れる ALTER 文を検出します。"
                    + "移行先が実際に書き換わるかは問わず、対象外の ALTER 文はありません。")
            .remedy("移行先の切り替えを条件分岐（IF・EVALUATE）または PERFORM 文の呼び分けに"
                    + "置き換え、ALTER 文を除いてください。")
            .example("""
                    ALTER SWITCH-PARA TO PROCEED TO ERROR-EXIT.
                    """, """
                    IF WS-ERROR-FLG = "Y"
                        PERFORM ERROR-EXIT
                    ELSE
                        PERFORM NORMAL-EXIT
                    END-IF.
                    """)
            .severity(Severity.HIGH)
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
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof SimpleStatement simple
                            && "ALTER".equals(CfgSupport.upper(simple.verb()))) {
                        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                                procedure.name()
                                        + " の ALTER 文が GO TO 文の移行先を実行時に書き換えます。",
                                new SourcePosition(model.sourceFile(),
                                        simple.range().start().line(), 1,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET)));
                    }
                });
            }
        }
        return findings;
    }
}
