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
 * R010 ALTER文の使用。ALTER文は GO TO の飛び先を実行時に書き換え、制御フローを静的に
 * 追えなくする。手続き部の ALTER 文をすべて検出する。
 */
public final class AlterStatementRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R010", "ALTER文による遷移先の動的変更", "制御フロー")
            .summary("手続き部の ALTER 文をすべて検出します。")
            .rationale("ALTER は GO TO の飛び先を実行時に書き換えるため、"
                    + "ソースを読んでも制御の流れを追えず、静的解析も遷移先を決められません。")
            .detection("手続き部に現れる ALTER 文を無条件に検出します。")
            .remedy("遷移先の切替を条件分岐(IF・EVALUATE)または PERFORM の呼び分けへ"
                    + "置き換え、ALTER を除きます。")
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
                                "ALTER文は GO TO の飛び先を実行時に書き換え、制御フローを"
                                        + "静的に追えなくする。",
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
