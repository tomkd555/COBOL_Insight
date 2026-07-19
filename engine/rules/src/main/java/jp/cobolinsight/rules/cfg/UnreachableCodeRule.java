package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.CfgNodeKind;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.Severity;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.ProcedureKind;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.Rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R011 到達不能コード・使われない段落。2つの下位判定を1ルールで返す。
 * (a) 到達不能コード: 構築済みCFGで ENTRY から到達できない STATEMENT ノードの文。
 * (b) 使われない段落: どの PERFORM・GO TO からも参照されず、本流の流下経路上にもない段落。
 * (b) はCFGの流下辺が過大近似となるため、CFG到達性ではなく意味モデルで判定する。
 */
public final class UnreachableCodeRule implements Rule {

    @Override
    public String id() {
        return "R011";
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (cfgs != null) {
                cfgs.of(model).ifPresent(cfg -> detectUnreachable(model, cfg, findings));
            }
            detectUnusedParagraphs(context, model, findings);
        }
        return findings;
    }

    /** (a) CFGでENTRYから到達できないSTATEMENTノードの文。 */
    private void detectUnreachable(CobolSemanticModel model, ControlFlowGraph cfg,
            List<Finding> findings) {
        Set<CfgNode> reachable = cfg.reachableNodes();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT || reachable.contains(node)) {
                continue;
            }
            node.statement().ifPresent(statement -> findings.add(Finding.of(id(),
                    defaultSeverity().toLevel(),
                    "この文は制御フロー上どの経路からも到達せず、実行されることがない。",
                    new SourcePosition(model.sourceFile(), statement.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET))));
        }
    }

    /** (b) 参照されず本流上にもない段落。 */
    private void detectUnusedParagraphs(AnalysisContext context, CobolSemanticModel model,
            List<Finding> findings) {
        List<Procedure> procedures = model.procedures();
        if (procedures.isEmpty()) {
            return;
        }
        Set<String> referenced = performTargets(model);
        Set<String> gotoTargets = allGotoTargets(context);
        Set<String> mainline = mainline(procedures);

        for (int i = 0; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            if (i == 0 || procedure.kind() != ProcedureKind.PARAGRAPH) {
                continue;
            }
            String name = CfgSupport.upper(procedure.name());
            if (referenced.contains(name) || gotoTargets.contains(name)
                    || mainline.contains(name)) {
                continue;
            }
            findings.add(Finding.of(id(), defaultSeverity().toLevel(),
                    "段落 " + procedure.name()
                            + " はどの PERFORM・GO TO からも参照されず、本流の流下経路上にもない。",
                    new SourcePosition(model.sourceFile(), procedure.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /** PERFORM の対象段落と、THRU 範囲(定義順の連続集合)に含まれる段落名。 */
    private static Set<String> performTargets(CobolSemanticModel model) {
        List<Procedure> procedures = model.procedures();
        Set<String> targets = new LinkedHashSet<>();
        for (PerformRelation perform : model.performs()) {
            targets.add(CfgSupport.upper(perform.targetProcedure()));
            if (perform.thruProcedure().isEmpty()) {
                continue;
            }
            int from = indexOf(procedures, perform.targetProcedure());
            int to = indexOf(procedures, perform.thruProcedure().get());
            if (from >= 0 && to >= from) {
                for (int i = from; i <= to; i++) {
                    targets.add(CfgSupport.upper(procedures.get(i).name()));
                }
            }
        }
        return targets;
    }

    private static Set<String> allGotoTargets(AnalysisContext context) {
        Set<String> targets = new LinkedHashSet<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof GoToStatement goTo) {
                        for (String target : goTo.targets()) {
                            targets.add(CfgSupport.upper(target));
                        }
                    }
                });
            }
        }
        return targets;
    }

    /**
     * 本流 = 先頭手続きから定義順に流下し、無条件終端(STOP/GOBACK/EXIT PROGRAM、または単一
     * 飛び先の無条件 GO TO)を含む段落に達した時点で打ち切る。打ち切りまでの段落名の集合。
     */
    private static Set<String> mainline(List<Procedure> procedures) {
        Set<String> mainline = new LinkedHashSet<>();
        for (Procedure procedure : procedures) {
            mainline.add(CfgSupport.upper(procedure.name()));
            if (hasUnconditionalExit(procedure)) {
                break;
            }
        }
        return mainline;
    }

    private static boolean hasUnconditionalExit(Procedure procedure) {
        for (Statement statement : procedure.statements()) {
            if (statement instanceof SimpleStatement simple) {
                String verb = CfgSupport.upper(simple.verb());
                if (verb.equals("STOP") || verb.equals("GOBACK")) {
                    return true;
                }
                if (verb.equals("EXIT")
                        && CfgSupport.upper(simple.text()).contains("PROGRAM")) {
                    return true;
                }
            } else if (statement instanceof GoToStatement goTo
                    && goTo.targets().size() == 1 && goTo.dependingOn().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static int indexOf(List<Procedure> procedures, String name) {
        String wanted = CfgSupport.upper(name);
        for (int i = 0; i < procedures.size(); i++) {
            if (CfgSupport.upper(procedures.get(i).name()).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }
}
