package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureKind;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R011 Unreachable code and unused paragraphs. Combines two sub-checks into one rule.
 * (a) Unreachable code: the statement of a STATEMENT node in the built CFG that cannot be
 * reached from ENTRY.
 * (b) Unused paragraph: a paragraph that is neither referenced by any PERFORM/GO TO nor on the
 * mainline fall-through path.
 * (b) is judged using the semantic model rather than CFG reachability, because the CFG's
 * fall-through edges are an over-approximation.
 */
public final class UnreachableCodeRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R011", "到達不能コード", "制御フロー")
            .summary("制御が到達しない文と、どこからも呼び出されない段落を検出します。")
            .rationale("実行されない記述が残ると、読む者が生きた処理と取り違え、"
                    + "実行されない場所に改修を加えます。")
            .detection("(a) プログラムの入口からどの経路をたどっても実行されない文と、"
                    + "(b) PERFORM・GO TO のいずれからも参照されず、前の段落から制御が移る経路も"
                    + "ない段落を検出します。手続き部の先頭の手続きと、節そのものは (b) の"
                    + "対象外です。")
            .remedy("不要なら削ってください。必要な処理なら、呼び出しか分岐を加えて"
                    + "実行されるようにしてください。")
            .example("""
                        GOBACK.
                        MOVE WS-A TO WS-B.
                    """, """
                        MOVE WS-A TO WS-B.
                        GOBACK.
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
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            if (cfgs != null) {
                cfgs.of(model).ifPresent(cfg -> detectUnreachable(model, cfg, findings));
            }
            detectUnusedParagraphs(context, model, findings);
        }
        return findings;
    }

    /** (a) The statement of a STATEMENT node unreachable from ENTRY in the CFG. */
    private void detectUnreachable(CobolSemanticModel model, ControlFlowGraph cfg,
            List<Finding> findings) {
        Set<CfgNode> reachable = cfg.reachableNodes();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT || reachable.contains(node)) {
                continue;
            }
            node.statement().ifPresent(statement -> {
                int line = statement.range().start().line();
                Statement leaver = lastReachableBefore(cfg, reachable, node, line);
                String file = model.sourceFile();
                List<CodeFlowStep> steps = new ArrayList<>();
                String cause = "";
                if (leaver != null) {
                    int at = leaver.range().start().line();
                    cause = "直前の " + label(leaver) + "（" + at + "行）で制御が移ります。";
                    steps.add(CfgSupport.step(file, at, "制御の移行（ここから戻りません）"));
                }
                steps.add(CfgSupport.step(file, line, "実行されない文"));
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        label(statement) + "に制御が到達しません。" + cause,
                        new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(new CodeFlow(steps)), List.of()));
            });
        }
    }

    /** The reachable statement of the same procedure that starts closest before {@code line}. */
    private static Statement lastReachableBefore(ControlFlowGraph cfg, Set<CfgNode> reachable,
            CfgNode target, int line) {
        Statement best = null;
        for (CfgNode node : cfg.nodes()) {
            if (!reachable.contains(node) || node.kind() != CfgNodeKind.STATEMENT
                    || !node.procedureName().equals(target.procedureName())) {
                continue;
            }
            Statement statement = node.statement().orElse(null);
            if (statement == null || statement.range().start().line() >= line) {
                continue;
            }
            if (best == null || statement.range().start().line() > best.range().start().line()) {
                best = statement;
            }
        }
        return best;
    }

    /** How a statement is named in a message: "MOVE 文", "GO TO 文", or plain "文". */
    private static String label(Statement statement) {
        if (statement instanceof SimpleStatement simple) {
            return CfgSupport.upper(simple.verb()) + " 文";
        }
        if (statement instanceof GoToStatement) {
            return "GO TO 文";
        }
        return "文";
    }

    /** (b) A paragraph that is neither referenced nor on the mainline path. */
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
            // The first procedure is the procedure division's entry point, and runs even if never referenced.
            if (i == 0 || procedure.kind() != ProcedureKind.PARAGRAPH) {
                continue;
            }
            String name = CfgSupport.upper(procedure.name());
            if (referenced.contains(name) || gotoTargets.contains(name)
                    || mainline.contains(name)) {
                continue;
            }
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    procedure.name() + " を呼ぶ PERFORM・GO TO がありません。"
                            + "前の段落から制御が移る経路もなく、実行される機会がありません。",
                    new SourcePosition(model.sourceFile(), procedure.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
    }

    /** PERFORM target paragraphs, plus paragraph names contained in a THRU range (a contiguous set in definition order). */
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
     * The mainline = falling through in definition order from the first procedure, stopping once
     * a paragraph containing an unconditional terminal (STOP/GOBACK/EXIT PROGRAM, or an
     * unconditional GO TO with a single target) is reached. The set of paragraph names up to that
     * stopping point.
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
