package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.RuleDoc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R029 CALL後のRETURN-CODE未検査。CALL 文の後、次の CALL または終端に達するまでの前方経路で
 * RETURN-CODE を条件参照しない CALL を検出する。ただし当該プログラムが RETURN-CODE を1回以上
 * 参照する場合に限る(RETURN-CODE を使う設計でこの CALL だけ無検査という不整合を捉える)。
 */
public final class ReturnCodeUncheckedRule implements Rule {

    @Override
    public String id() {
        return "R029";
    }

    @Override
    public RuleDoc doc() {
        return RuleDoc.named("呼び出し先プログラムの戻りコード(RETURN-CODE)未検査", "制御フロー")
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
                .build();
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.MEDIUM;
    }

    @Override
    public AnalysisPhase phase() {
        return AnalysisPhase.CONTROL_FLOW;
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
                findings.add(Finding.of(id(), defaultSeverity().toLevel(),
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
