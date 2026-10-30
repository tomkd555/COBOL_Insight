package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.analysis.dataflow.CfgBuilder;
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
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.ProcedureNames;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * R011 Unreachable code and unused paragraphs. Combines two sub-checks into one rule.
 * (a) Unreachable code: the statement of a STATEMENT node in the built CFG that cannot be
 * reached from ENTRY.
 * (b) Unused paragraph: a paragraph that no PERFORM, GO TO (including one inside a READ's
 * AT END or an ON SIZE ERROR phrase), ALTER, WHENEVER or EXEC CICS HANDLE names, and that
 * control does not fall into from the paragraph before it.
 * (b) is judged on the semantic model, because the CFG's fall-through edges over-approximate.
 */
public final class UnreachableCodeRule implements Rule {

    private static final RuleMeta META = RuleMeta.named("R011", "到達不能コード", "制御フロー")
            .summary("制御が到達しない文と、どこからも呼び出されない段落を検出します。")
            .rationale("実行されない記述が残ると、読む者が生きた処理と取り違え、"
                    + "実行されない場所に改修を加えます。")
            .detection("(a) プログラムの入口からどの経路をたどっても実行されない文と、"
                    + "(b) PERFORM・GO TO・ALTER・HANDLE CONDITION のいずれからも参照されず、"
                    + "前の段落から制御が移る経路もない段落を検出します。READ の AT END などの"
                    + "句に書いた GO TO も参照に数えます。PERFORM THRU の範囲の中を流れた制御は、"
                    + "範囲の終端の段落で PERFORM へ戻るものとみなします。"
                    + "手続き部の先頭の手続きと、節そのものは (b) の対象外です。"
                    + "(b) で報告した段落の中の文は (a) で重ねて報告しません。"
                    + "EXEC CICS RETURN の後に念のため書いた GOBACK のように、到達しない"
                    + "STOP RUN・GOBACK・EXIT PROGRAM は動作を変えないため (a) では報告しません。")
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
            ProcedureMap procedureMap = new ProcedureMap(model);
            Set<Integer> unused = detectUnusedParagraphs(model, procedureMap, findings);
            if (cfgs != null) {
                cfgs.of(model).ifPresent(cfg ->
                        detectUnreachable(model, cfg, procedureMap, unused, findings));
            }
        }
        return findings;
    }

    /**
     * (a) The statement of a STATEMENT node unreachable from ENTRY in the CFG. The statements of
     * a paragraph (b) already reported stand under that one finding.
     */
    private void detectUnreachable(CobolSemanticModel model, ControlFlowGraph cfg,
            ProcedureMap procedureMap, Set<Integer> unusedParagraphs, List<Finding> findings) {
        Set<CfgNode> reachable = cfg.reachableNodes();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT || reachable.contains(node)
                    || node.statement().map(procedureMap::procedureOf)
                            .filter(unusedParagraphs::contains).isPresent()
                    || node.statement().filter(UnreachableCodeRule::ends).isPresent()) {
                continue;
            }
            node.statement().ifPresent(statement -> {
                int line = statement.range().start().line();
                Statement leaver = lastReachableBefore(cfg, reachable, procedureMap, statement);
                // A statement a COPY member brings in stands at a line of the member's own file.
                String file = statement.range().start().file();
                List<CodeFlowStep> steps = new ArrayList<>();
                String cause = "";
                if (leaver != null) {
                    int at = leaver.range().start().line();
                    cause = "直前の " + label(leaver) + "（" + at + "行）で制御が移ります。";
                    steps.add(CfgSupport.step(leaver.range().start().file(), at,
                            "制御の移行（ここから戻りません）"));
                }
                steps.add(CfgSupport.step(file, line, "実行されない文"));
                findings.add(new Finding(META.id(), META.defaultSeverity().toLevel(),
                        label(statement) + "に制御が到達しません。" + cause,
                        new SourcePosition(file, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(new CodeFlow(steps)), List.of()));
            });
        }
    }

    /**
     * Whether a statement only ends the program: STOP, GOBACK or EXIT PROGRAM. One that control
     * cannot reach, such as the GOBACK written after an EXEC CICS RETURN to be safe, changes
     * nothing whether it stays or goes, so (a) leaves it unreported.
     */
    private static boolean ends(Statement statement) {
        if (!(statement instanceof SimpleStatement simple)) {
            return false;
        }
        String verb = CfgSupport.upper(simple.verb());
        return verb.equals("STOP") || verb.equals("GOBACK")
                || (verb.equals("EXIT") && CfgSupport.upper(simple.text()).contains("PROGRAM"));
    }

    /**
     * The reachable statement of the procedure that holds {@code target}, by the procedure map,
     * that starts closest before it in the same file: lines of two files say nothing about
     * which comes first.
     */
    private static Statement lastReachableBefore(ControlFlowGraph cfg, Set<CfgNode> reachable,
            ProcedureMap procedureMap, Statement target) {
        int procedure = procedureMap.procedureOf(target);
        String file = target.range().start().file();
        int line = target.range().start().line();
        Statement best = null;
        for (CfgNode node : cfg.nodes()) {
            if (!reachable.contains(node) || node.kind() != CfgNodeKind.STATEMENT) {
                continue;
            }
            Statement statement = node.statement().orElse(null);
            if (statement == null || procedureMap.procedureOf(statement) != procedure
                    || !statement.range().start().file().equals(file)
                    || statement.range().start().line() >= line) {
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
     * (b) A paragraph that nothing reaches: no PERFORM, GO TO, ALTER, HANDLE or WHENEVER names it,
     * and control does not fall into it from the paragraph before. Names resolve through
     * {@link ProcedureNames}, so a name two sections define reaches the paragraph the statement's
     * own section, or its qualifier, says. Returns the indices of the paragraphs it reported.
     */
    private Set<Integer> detectUnusedParagraphs(CobolSemanticModel model, ProcedureMap procedureMap,
            List<Finding> findings) {
        Set<Integer> unused = new LinkedHashSet<>();
        List<Procedure> procedures = model.procedures();
        if (procedures.isEmpty()) {
            return unused;
        }
        ProcedureNames names = new ProcedureNames(procedures);
        Set<Integer> reached = new HashSet<>();
        List<ProcedureNames.Range> ranges = performTargets(model, names, procedureMap, reached);
        reached.addAll(wheneverTargets(model, names, procedureMap));
        // The first procedure is the procedure division's entry point, and runs even if never
        // referenced.
        Set<Integer> fellInto = new HashSet<>(Set.of(0));
        List<int[]> jumps = jumps(model, names);
        for (int[] jump : jumps) {
            reached.add(jump[1]);
            if (!insideOneRange(jump[0], jump[1], ranges)) {
                fellInto.add(jump[1]);
            }
        }
        // Control that arrives from the entry, by falling in, or by a GO TO from outside every
        // range that holds its target keeps going into the next paragraph unless the paragraph
        // leaves unconditionally, and a GO TO it takes carries it on to the GO TO's target, inside
        // a range or not. Control that runs inside a PERFORM's range, a GO TO between two of its
        // paragraphs included, returns to the PERFORM at the range's end and reaches no paragraph
        // past it. The two rules feed each other, so they run until nothing changes.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i + 1 < procedures.size(); i++) {
                if (fellInto.contains(i)
                        && CfgBuilder.canCompleteNormally(procedures.get(i).statements())) {
                    changed |= fellInto.add(i + 1);
                }
            }
            for (int[] jump : jumps) {
                if (fellInto.contains(jump[0])) {
                    changed |= fellInto.add(jump[1]);
                }
            }
        }
        reached.addAll(fellInto);

        for (int i = 1; i < procedures.size(); i++) {
            Procedure procedure = procedures.get(i);
            if (procedure.kind() != ProcedureKind.PARAGRAPH || reached.contains(i)) {
                continue;
            }
            unused.add(i);
            findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                    procedure.name() + " を呼ぶ PERFORM・GO TO がありません。"
                            + "前の段落から制御が移る経路もなく、実行される機会がありません。",
                    new SourcePosition(procedure.range().start().file(),
                            procedure.range().start().line(), 1,
                            SourcePosition.UNKNOWN_BYTE_OFFSET)));
        }
        return unused;
    }

    /**
     * Every procedure of a PERFORM's range, a THRU range or a performed section's paragraphs,
     * goes to {@code reached}. Returns the ranges.
     */
    private static List<ProcedureNames.Range> performTargets(CobolSemanticModel model,
            ProcedureNames names, ProcedureMap procedureMap, Set<Integer> reached) {
        List<ProcedureNames.Range> ranges = new ArrayList<>();
        for (PerformRelation perform : model.performs()) {
            names.rangeOf(perform, procedureMap.sectionOf(perform)).ifPresent(range -> {
                ranges.add(range);
                for (int i = range.from(); i <= range.to(); i++) {
                    reached.add(i);
                }
            });
        }
        return ranges;
    }

    /**
     * Procedures an EXEC SQL WHENEVER branches to: the code the precompiler generates calls them. A
     * WHENEVER in the PROCEDURE DIVISION names its target from its own section; one written
     * before it, from outside any section.
     */
    private static Set<Integer> wheneverTargets(CobolSemanticModel model, ProcedureNames names,
            ProcedureMap procedureMap) {
        Set<Integer> targets = new HashSet<>();
        // By identity: two inclusions of one copybook give two blocks of one range.
        Set<EmbeddedBlock> inProcedures = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Procedure> procedures = model.procedures();
        for (Procedure procedure : procedures) {
            Optional<String> section = ProcedureNames.sectionOf(procedure);
            CfgSupport.walk(procedure.statements(), statement -> {
                if (statement instanceof SimpleStatement simple
                        && "EXEC SQL".equals(simple.verb())) {
                    inProcedures.addAll(procedureMap.blocksOf(simple));
                    WheneverClause.branchOf(simple.text()).ifPresent(branch ->
                            add(targets, names.indexOf(branch.target(), Optional.empty(),
                                    section)));
                }
            });
        }
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() == EmbeddedBlockKind.SQL && !inProcedures.contains(block)) {
                WheneverClause.branchOf(block.text()).ifPresent(branch -> add(targets,
                        names.indexOf(branch.target(), Optional.empty(), Optional.empty())));
            }
        }
        return targets;
    }

    /**
     * GO TO targets of this program, including a GO TO inside another statement's phrase, an
     * ALTER's PROCEED TO and the labels of an EXEC CICS HANDLE, which CICS branches to the way a
     * GO TO would: each as the index of the branching procedure and of its target.
     */
    private static List<int[]> jumps(CobolSemanticModel model, ProcedureNames names) {
        List<int[]> jumps = new ArrayList<>();
        List<Procedure> procedures = model.procedures();
        for (int p = 0; p < procedures.size(); p++) {
            int source = p;
            Optional<String> section = ProcedureNames.sectionOf(procedures.get(p));
            CfgSupport.walk(procedures.get(p).statements(), statement -> {
                if (statement instanceof GoToStatement goTo) {
                    for (int t = 0; t < goTo.targets().size(); t++) {
                        add(jumps, source, names.indexOf(goTo.targets().get(t),
                                goTo.targetSections().get(t), section));
                    }
                } else if (statement instanceof SimpleStatement simple) {
                    for (NestedBranches.Branch branch : NestedBranches.allBranches(simple.text())) {
                        add(jumps, source,
                                names.indexOf(branch.target(), branch.section(), section));
                    }
                }
            });
        }
        return jumps;
    }

    private static void add(List<int[]> jumps, int source, int target) {
        if (target >= 0) {
            jumps.add(new int[] {source, target});
        }
    }

    /** Whether one PERFORM range holds both the branching procedure and its target. */
    private static boolean insideOneRange(int source, int target,
            List<ProcedureNames.Range> ranges) {
        return ranges.stream().anyMatch(range -> range.from() <= source && source <= range.to()
                && range.from() <= target && target <= range.to());
    }

    private static void add(Set<Integer> targets, int index) {
        if (index >= 0) {
            targets.add(index);
        }
    }
}
