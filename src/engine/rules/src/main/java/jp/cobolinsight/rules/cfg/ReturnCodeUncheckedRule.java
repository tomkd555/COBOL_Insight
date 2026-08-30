package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R029 Unchecked RETURN-CODE after a CALL. Detects a CALL statement whose forward path, up to
 * the next CALL or the program's terminal point, never references RETURN-CODE in a condition.
 * This applies only when the program references RETURN-CODE at least once elsewhere (catching
 * the inconsistency of a design that uses RETURN-CODE everywhere except this one unchecked
 * CALL).
 */
public final class ReturnCodeUncheckedRule implements Rule {

    private static final RuleMeta META = RuleMeta
            .named("R029", "呼び出し先プログラムの戻りコード(RETURN-CODE)未検査", "制御フロー")
            .summary("RETURN-CODE を使う設計のプログラムで、"
                    + "その検査を伴わない CALL を検出します。")
            .rationale("呼び出し先の失敗に気付かないまま後続が進みます。"
                    + "同じプログラム内で検査している CALL と扱いが不揃いになる点も誤りの兆候です。")
            .detection("CALL の後、次の CALL または終端に達するまでの前方経路で"
                    + "RETURN-CODE を条件参照しないものを検出します。"
                    + "プログラム内で RETURN-CODE を1回以上参照している場合に限ります。")
            .remedy("CALL の直後に RETURN-CODE を判定し、正常値以外を異常として処理します。")
            .example("""
                    CALL "SUBPGM2" USING WS-PARM.
                    MOVE WS-PARM TO WS-OUT.
                    """, """
                    CALL "SUBPGM2" USING WS-PARM.
                    IF RETURN-CODE NOT = ZERO
                        PERFORM CALL-ERROR
                    END-IF.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT, Command.REPORT)
            .targets(AssetKind.COBOL)
            .needs(Needs.SEMANTIC, Needs.CFG)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (!referencesReturnCode(model)) {
                continue;
            }
            cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, findings));
        }
        return findings;
    }

    private void evaluate(CobolSemanticModel model, ControlFlowGraph cfg, List<Finding> findings) {
        for (CfgNode node : cfg.nodes()) {
            if (!isCall(node)) {
                continue;
            }
            boolean checked = CfgSupport.forwardHasMatch(cfg, node,
                    ReturnCodeUncheckedRule::isCall,
                    ReturnCodeUncheckedRule::referencesReturnCodeInCondition);
            if (!checked) {
                findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                        "CALL の後、RETURN-CODE を検査しないまま後続処理へ進んでいる。"
                                + "他所では RETURN-CODE を参照しており、検査の欠落が不整合となる。",
                        new SourcePosition(model.sourceFile(),
                                node.statement().orElseThrow().range().start().line(), 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)));
            }
        }
    }

    private static boolean isCall(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof SimpleStatement simple
                        && "CALL".equals(CfgSupport.upper(simple.verb())))
                .orElse(false);
    }

    private static boolean referencesReturnCodeInCondition(CfgNode node) {
        return node.statement()
                .map(statement -> statement instanceof CompoundStatement compound
                        && compound.conditionText().toUpperCase(Locale.ROOT)
                                .contains("RETURN-CODE"))
                .orElse(false);
    }

    private static boolean referencesReturnCode(CobolSemanticModel model) {
        AtomicBoolean found = new AtomicBoolean(false);
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                if (mentionsReturnCode(statement)) {
                    found.set(true);
                }
            });
        }
        return found.get();
    }

    private static boolean mentionsReturnCode(Statement statement) {
        String text = switch (statement) {
            case SimpleStatement simple -> simple.text();
            case CompoundStatement compound -> compound.conditionText();
            default -> "";
        };
        return text.toUpperCase(Locale.ROOT).contains("RETURN-CODE");
    }
}
