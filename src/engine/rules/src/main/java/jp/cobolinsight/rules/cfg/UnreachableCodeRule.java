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
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.NestedBranches;
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
import jp.cobolinsight.core.sql.WheneverClause;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * R011 Unreachable code and unused paragraphs. Combines two sub-checks into one rule.
 * (a) Unreachable code: the statement of a STATEMENT node in the built CFG that cannot be
 * reached from ENTRY.
 * (b) Unused paragraph: a paragraph that no PERFORM, GO TO (including one inside a READ's
 * AT END or an ON SIZE ERROR phrase), ALTER, WHENEVER or EXEC CICS HANDLE names, and that
 * control does not fall into from the paragraph before it.
 * (b) is judged using the semantic model rather than CFG reachability, because the CFG's
 * fall-through edges are an over-approximation.
 */
public final class UnreachableCodeRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R011", "到達不能コード", "制御フロー")
            .summary("制御が到達しない文と、どこからも呼び出されない段落を検出します。")
            .rationale("実行されない記述が残ると、読む者が生きた処理と取り違え、"
                    + "実行されない場所に改修を加えます。")
            .detection("(a) プログラムの入口からどの経路をたどっても実行されない文と、"
                    + "(b) PERFORM・GO TO・ALTER・HANDLE CONDITION のいずれからも参照されず、"
                    + "前の段落から制御が移る経路もない段落を検出します。READ の AT END などの"
                    + "句に書いた GO TO も参照に数えます。PERFORM THRU の終端の段落からは次の"
                    + "段落へ制御が移らないものとみなします。"
                    + "手続き部の先頭の手続きと、節そのものは (b) の対象外です。"
                    + "(b) で報告した段落の中の文は (a) で重ねて報告しません。")
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
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG)
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
            Set<String> unused = detectUnusedParagraphs(context, model, findings);
            if (cfgs != null) {
                cfgs.of(model).ifPresent(cfg -> detectUnreachable(model, cfg, unused, findings));
            }
        }
        return findings;
    }

    /**
     * (a) The statement of a STATEMENT node unreachable from ENTRY in the CFG. Statements of a
     * paragraph already reported by (b) are that one finding, not one more each.
     */
    private void detectUnreachable(CobolSemanticModel model, ControlFlowGraph cfg,
            Set<String> unusedParagraphs, List<Finding> findings) {
        Set<CfgNode> reachable = cfg.reachableNodes();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT || reachable.contains(node)
                    || unusedParagraphs.contains(CfgSupport.upper(node.procedureName()))) {
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

    /**
     * (b) A paragraph that nothing reaches: no PERFORM, GO TO, ALTER or WHENEVER names it, and
     * control does not fall into it from the paragraph before. Returns the names it reported.
     */
    private Set<String> detectUnusedParagraphs(AnalysisContext context,
            CobolSemanticModel model, List<Finding> findings) {
        Set<String> unused = new LinkedHashSet<>();
        List<Procedure> procedures = model.procedures();
        if (procedures.isEmpty()) {
            return unused;
        }
        Set<String> reached = new LinkedHashSet<>();
        Set<String> flowing = new LinkedHashSet<>();
        Set<String> rangeEnds = new LinkedHashSet<>();
        performTargets(model, reached, flowing, rangeEnds);
        reached.addAll(wheneverTargets(model));
        Set<String> gotoTargets = allGotoTargets(context);
        reached.addAll(gotoTargets);
        flowing.addAll(gotoTargets);
        // The first procedure is the procedure division's entry point, and runs even if never
        // referenced.
        flowing.add(CfgSupport.upper(procedures.get(0).name()));
        // Control that arrives by GO TO, by falling in, or from the entry keeps going into the
        // next paragraph unless the paragraph leaves unconditionally. A single-paragraph
        // PERFORM returns at the end of its paragraph instead, and so does the end of a THRU
        // range or a performed section: what follows it is not reached through the range.
        for (int i = 0; i + 1 < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            String name = CfgSupport.upper(procedure.name());
            if (flowing.contains(name) && !rangeEnds.contains(name)
                    && !hasUnconditionalExit(procedure)) {
                String next = CfgSupport.upper(procedures.get(i + 1).name());
                reached.add(next);
                flowing.add(next);
            }
        }
        reached.addAll(flowing);

        for (int i = 1; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            if (procedure.kind() != ProcedureKind.PARAGRAPH
                    || reached.contains(CfgSupport.upper(procedure.name()))) {
                continue;
            }
            unused.add(CfgSupport.upper(procedure.name()));
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    procedure.name() + " を呼ぶ PERFORM・GO TO がありません。"
                            + "前の段落から制御が移る経路もなく、実行される機会がありません。",
                    new SourcePosition(model.sourceFile(), procedure.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
        return unused;
    }

    /**
     * PERFORM targets go to {@code reached}. Inside a THRU range or a performed section, every
     * paragraph but the last also flows into the one after it, so those go to {@code flowing};
     * the last one returns to the PERFORM and goes to {@code rangeEnds}.
     */
    private static void performTargets(CobolSemanticModel model, Set<String> reached,
            Set<String> flowing, Set<String> rangeEnds) {
        List<Procedure> procedures = model.procedures();
        for (PerformRelation perform : model.performs()) {
            reached.add(CfgSupport.upper(perform.targetProcedure()));
            int from = indexOf(procedures, perform.targetProcedure());
            if (from < 0) {
                continue;
            }
            int to = perform.thruProcedure().map(name -> indexOf(procedures, name)).orElse(-1);
            if (to < from && procedures.get(from).kind() == ProcedureKind.SECTION) {
                to = lastParagraphOfSection(procedures, from);
            }
            for (int i = from; i <= to; i++) {
                reached.add(CfgSupport.upper(procedures.get(i).name()));
                if (i < to) {
                    flowing.add(CfgSupport.upper(procedures.get(i).name()));
                } else if (to > from) {
                    rangeEnds.add(CfgSupport.upper(procedures.get(i).name()));
                }
            }
        }
    }


    private static int lastParagraphOfSection(List<Procedure> procedures, int section) {
        String name = CfgSupport.upper(procedures.get(section).name());
        int last = section;
        for (int i = section + 1; i < procedures.size(); i++) {
            Procedure paragraph = procedures.get(i);
            if (paragraph.kind() != ProcedureKind.PARAGRAPH
                    || !paragraph.sectionName().map(CfgSupport::upper).orElse("").equals(name)) {
                break;
            }
            last = i;
        }
        return last;
    }

    /** Procedures an EXEC SQL WHENEVER branches to: the precompiler calls them, not a PERFORM. */
    private static Set<String> wheneverTargets(CobolSemanticModel model) {
        Set<String> targets = new LinkedHashSet<>();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() == EmbeddedBlockKind.SQL) {
                WheneverClause.branchOf(block.text())
                        .ifPresent(branch -> targets.add(CfgSupport.upper(branch.target())));
            }
        }
        return targets;
    }

    /**
     * GO TO targets, including a GO TO inside another statement's phrase, an ALTER's PROCEED TO
     * and the labels of an EXEC CICS HANDLE, which CICS branches to the way a GO TO would.
     */
    private static Set<String> allGotoTargets(AnalysisContext context) {
        Set<String> targets = new LinkedHashSet<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            for (Procedure procedure : model.procedures()) {
                CfgSupport.walk(procedure.statements(), statement -> {
                    if (statement instanceof GoToStatement goTo) {
                        for (String target : goTo.targets()) {
                            targets.add(CfgSupport.upper(target));
                        }
                    } else if (statement instanceof SimpleStatement simple) {
                        for (String target : NestedBranches.goToTargets(simple.text())) {
                            targets.add(CfgSupport.upper(target));
                        }
                        for (String target : NestedBranches.alterTargets(simple.text())) {
                            targets.add(CfgSupport.upper(target));
                        }
                        for (String target : NestedBranches.handleTargets(simple.text())) {
                            targets.add(CfgSupport.upper(target));
                        }
                    }
                });
            }
        }
        return targets;
    }

    /** STOP/GOBACK/EXIT PROGRAM, or an unconditional GO TO with a single target. */
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
