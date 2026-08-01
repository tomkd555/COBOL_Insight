package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.List;

/**
 * R010 ALTER文の使用。ALTER文は GO TO の飛び先を実行時に書き換え、制御フローを静的に
 * 追えなくする。手続き部の ALTER 文をすべて検出する。
 */
public final class AlterStatementRule implements Rule {

    @Override
    public String id() {
        return "R010";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("ALTER文による遷移先の動的変更", "制御フロー")
                .summary("手続き部の ALTER 文をすべて検出する。")
                .rationale("ALTER は GO TO の飛び先を実行時に書き換えるため、"
                        + "ソースを読んでも制御の流れを追えず、静的解析も遷移先を決められない。")
                .detection("手続き部に現れる ALTER 文を無条件に検出する。")
                .remedy("遷移先の切替を条件分岐(IF・EVALUATE)または PERFORM の呼び分けへ"
                        + "置き換え、ALTER を除く。")
                .example("""
                        ALTER SWITCH-PARA TO PROCEED TO ERROR-EXIT.
                        """, """
                        IF WS-ERROR-FLG = "Y"
                            PERFORM ERROR-EXIT
                        ELSE
                            PERFORM NORMAL-EXIT
                        END-IF.
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof SimpleStatement simple
                            && "ALTER".equals(CfgSupport.upper(simple.verb()))) {
                        findings.add(Finding.of(id(), defaultSeverity().toLevel(),
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
