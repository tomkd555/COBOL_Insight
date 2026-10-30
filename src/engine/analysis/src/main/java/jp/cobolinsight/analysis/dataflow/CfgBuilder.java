package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.NestedBranches;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.ProcedureMap;
import jp.cobolinsight.core.semantic.ProcedureNames;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.sql.WheneverClause;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds a control flow graph from the normalized semantic model. Edges are created for the
 * paragraph/section sequence, IF/EVALUATE branches, inline PERFORM iteration, GO TO branches,
 * STOP RUN/GOBACK/EXIT PROGRAM terminators, fall-through from a paragraph's end to the next
 * paragraph, and paragraph PERFORM (including THRU ranges) call/return.
 *
 * <p>A PERFORM's return edge is context-insensitive (drawn from the end of the range to the
 * successors of every call site), so paths are an over-approximation. This approximation is
 * sufficient for reachability queries (whether a check exists, whether a point is reachable).
 */
public final class CfgBuilder {

    /** Result of building the node list for a procedure division. first is the head node (null for an empty list); ends are the trailing reach points. */
    private record Chain(CfgNode first, List<CfgNode> ends) {
    }

    /**
     * A branch to resolve once every procedure has its chain. {@code section} is the section of
     * the statement that branches; {@code qualifiers} runs parallel to {@code targets}.
     */
    private record PendingGoTo(CfgNode node, Optional<String> section, List<String> targets,
            List<Optional<String>> qualifiers) {
    }

    private final CobolSemanticModel model;
    private final List<CfgNode> nodes = new ArrayList<>();
    private final Map<CfgNode, LinkedHashSet<CfgNode>> successors = new LinkedHashMap<>();
    private final List<CfgNode> terminators = new ArrayList<>();
    private final List<PendingGoTo> gotos = new ArrayList<>();
    /** Which statement writes each PERFORM relation, as {@link ProcedureMap} assigns them. */
    private final ProcedureMap procedureMap;
    /**
     * The PERFORM relations each statement node makes, in model order: a PERFORM statement's own,
     * the INPUT and OUTPUT PROCEDURE of a SORT or MERGE, and a PERFORM written in a phrase of the
     * statement. Each relation belongs to one node, so the two statements a COPY member included
     * twice gives the same range each keep their own.
     */
    private final Map<CfgNode, List<PerformRelation>> performsByNode = new LinkedHashMap<>();
    /** The section of each node in {@link #performsByNode}. */
    private final Map<CfgNode, Optional<String>> sectionByPerformNode = new HashMap<>();
    /** The section of the procedure whose statements are being built. */
    private Optional<String> currentSection = Optional.empty();
    private int nextId;

    private CfgBuilder(CobolSemanticModel model) {
        this.model = model;
        this.procedureMap = new ProcedureMap(model);
    }

    public static ControlFlowGraph build(CobolSemanticModel model) {
        return new CfgBuilder(model).run();
    }

    private ControlFlowGraph run() {
        CfgNode entry = newNode(CfgNodeKind.ENTRY, null, "");
        List<Procedure> procedures = model.procedures();
        List<Chain> chains = new ArrayList<>();
        for (Procedure procedure : procedures) {
            currentSection = ProcedureNames.sectionOf(procedure);
            chains.add(buildSeq(procedure.statements(), procedure.name()));
        }
        CfgNode exit = newNode(CfgNodeKind.EXIT, null, "");

        // A name resolves the way the stored paragraph flow resolves it: see ProcedureNames.
        ProcedureNames names = new ProcedureNames(procedures);

        // entry -> first executable statement. End of a paragraph -> start of the next paragraph
        // (fall-through). End of the last paragraph -> exit.
        addEdge(entry, effectiveEntry(chains, 0, exit));
        for (int i = 0; i < chains.size(); i++) {
            CfgNode next = effectiveEntry(chains, i + 1, exit);
            for (CfgNode end : chains.get(i).ends()) {
                addEdge(end, next);
            }
        }

        // Before drawing PERFORM call/return edges, record each PERFORM statement node's
        // sequential successor (the return point).
        Map<CfgNode, List<CfgNode>> returnPointsByPerform = new LinkedHashMap<>();
        for (CfgNode node : performsByNode.keySet()) {
            returnPointsByPerform.put(node, List.copyOf(successorsOf(node)));
        }

        for (PendingGoTo pending : gotos) {
            for (int t = 0; t < pending.targets().size(); t++) {
                int index = names.indexOf(pending.targets().get(t), pending.qualifiers().get(t),
                        pending.section());
                if (index >= 0) {
                    addEdge(pending.node(), effectiveEntry(chains, index, exit));
                }
            }
        }

        performsByNode.forEach((node, performs) -> {
            Optional<String> section = sectionByPerformNode.get(node);
            for (PerformRelation perform : performs) {
                Optional<ProcedureNames.Range> range = names.rangeOf(perform, section);
                if (range.isEmpty()) {
                    continue;
                }
                int target = range.get().from();
                int thru = range.get().to();
                addEdge(node, effectiveEntry(chains, target, exit));
                List<CfgNode> returnPoints = new ArrayList<>(returnPointsByPerform.get(node));
                if (isUntilPerform(node, perform)) {
                    // PERFORM ... UNTIL represents an iteration that, after returning, loops back
                    // to its own node (the condition check)
                    returnPoints.add(node);
                }
                for (CfgNode end : rangeEnds(chains, target, thru)) {
                    for (CfgNode point : returnPoints) {
                        addEdge(end, point);
                    }
                }
            }
        });

        for (CfgNode terminator : terminators) {
            addEdge(terminator, exit);
        }

        Map<CfgNode, List<CfgNode>> edges = new LinkedHashMap<>();
        successors.forEach((node, set) -> edges.put(node, List.copyOf(set)));
        return new ControlFlowGraph(model.programId(), model.sourceFile(), nodes, entry, exit,
                edges);
    }

    /**
     * The reach points at the end of a performed range: those of its last procedure that has
     * statements, since a procedure with none passes control straight on.
     */
    private static List<CfgNode> rangeEnds(List<Chain> chains, int from, int to) {
        for (int i = to; i >= from; i--) {
            if (chains.get(i).first() != null) {
                return chains.get(i).ends();
            }
        }
        return List.of();
    }

    /** Head node of the first procedure at or after index that has statements. If none, the exit node. */
    private CfgNode effectiveEntry(List<Chain> chains, int index, CfgNode exit) {
        for (int i = index; i < chains.size(); i++) {
            if (chains.get(i).first() != null) {
                return chains.get(i).first();
            }
        }
        return exit;
    }

    private Chain buildSeq(List<Statement> statements, String procedureName) {
        CfgNode first = null;
        List<CfgNode> open = List.of();
        for (Statement statement : statements) {
            Chain result = buildStatement(statement, procedureName);
            if (first == null) {
                first = result.first();
            }
            for (CfgNode end : open) {
                addEdge(end, result.first());
            }
            open = result.ends();
        }
        return new Chain(first, open);
    }

    /** Builds the node and edges for a single statement. first is the statement's entry node; ends are the reach points after execution. */
    private Chain buildStatement(Statement statement, String procedureName) {
        CfgNode node = newNode(CfgNodeKind.STATEMENT, statement, procedureName);
        if (statement instanceof SimpleStatement simple) {
            if (isTerminator(simple)) {
                terminators.add(node);
                return new Chain(node, List.of());
            }
            String verb = simple.verb();
            List<PerformRelation> performs = procedureMap.performsOf(simple);
            if (!performs.isEmpty()) {
                performsByNode.put(node, performs);
                sectionByPerformNode.put(node, currentSection);
            }
            if ("EXEC SQL".equals(verb)) {
                // WHENEVER ... GO TO/PERFORM x: the precompiler branches to x after every later
                // SQL statement. One edge from the WHENEVER itself keeps x reachable without
                // pretending to know which statement raises the condition.
                WheneverClause.branchOf(simple.text()).ifPresent(branch ->
                        gotos.add(new PendingGoTo(node, currentSection,
                                List.of(branch.target()), List.of(Optional.empty()))));
            } else {
                // READ ... AT END GO TO x, INVALID KEY GO TO x, ON SIZE ERROR GO TO x, the
                // PROCEED TO target of an ALTER and a HANDLE label: a conditional branch that
                // also falls through.
                List<NestedBranches.Branch> nested = NestedBranches.allBranches(simple.text());
                if (!nested.isEmpty()) {
                    gotos.add(new PendingGoTo(node, currentSection,
                            nested.stream().map(NestedBranches.Branch::target).toList(),
                            nested.stream().map(NestedBranches.Branch::section).toList()));
                }
            }
            return new Chain(node, List.of(node));
        }
        if (statement instanceof GoToStatement goTo) {
            if (!goTo.targets().isEmpty()) {
                gotos.add(new PendingGoTo(node, currentSection, goTo.targets(),
                        goTo.targetSections()));
            }
            return new Chain(node, fallsThrough(goTo) ? List.of(node) : List.of());
        }
        CompoundStatement compound = (CompoundStatement) statement;
        List<CfgNode> ends = new ArrayList<>();
        if (runsOnce(compound)) {
            // An inline PERFORM with no UNTIL, VARYING or TIMES runs its body once, in sequence.
            Chain bodyChain = buildSeq(compound.blocks().get(0).statements(), procedureName);
            if (bodyChain.first() == null) {
                return new Chain(node, List.of(node));
            }
            addEdge(node, bodyChain.first());
            return new Chain(node, bodyChain.ends());
        }
        if (compound.kind() == ControlKind.LOOP) {
            StatementBlock body = compound.blocks().get(0);
            Chain bodyChain = buildSeq(body.statements(), procedureName);
            if (bodyChain.first() != null) {
                addEdge(node, bodyChain.first());
                for (CfgNode end : bodyChain.ends()) {
                    addEdge(end, node);
                }
            }
            ends.add(node);
            return new Chain(node, ends);
        }
        // Branch (IF/EVALUATE). A branch without ELSE/WHEN OTHER has a path that takes none of
        // the arms, so the branch node itself is added to the post-execution reach points and
        // falls through to what follows.
        boolean exhaustive = isExhaustive(compound);
        for (StatementBlock block : compound.blocks()) {
            Chain blockChain = buildSeq(block.statements(), procedureName);
            if (blockChain.first() == null) {
                if (!ends.contains(node)) {
                    ends.add(node);
                }
            } else {
                addEdge(node, blockChain.first());
                ends.addAll(blockChain.ends());
            }
        }
        if (!exhaustive && !ends.contains(node)) {
            ends.add(node);
        }
        return new Chain(node, ends);
    }

    /**
     * Whether this statement terminates execution: STOP, GOBACK, EXIT PROGRAM, and the EXEC CICS
     * RETURN (with or without TRANSID) and XCTL that hand control back to CICS or to another
     * program for good. A RETURN or XCTL written with RESP, RESP2 or NOHANDLE comes back to the
     * next statement when it fails, so it goes on. EXIT PARAGRAPH and the like fall through.
     *
     * <p>Made public because the scan that derives flow between paragraphs needs the same
     * judgment. Encoding the same COBOL semantics in two places risks fixing only one of them.
     */
    public static boolean isTerminator(SimpleStatement statement) {
        String verb = statement.verb();
        if ("STOP".equals(verb) || "GOBACK".equals(verb)) {
            return true;
        }
        if ("EXEC CICS".equals(verb)) {
            String command = CICS_COMMAND.matcher(statement.text()).replaceFirst("$1");
            // With RESP, RESP2 or NOHANDLE a RETURN or XCTL that fails comes back to the next
            // statement, which then reads the response.
            return (command.equalsIgnoreCase("RETURN") || command.equalsIgnoreCase("XCTL"))
                    && !COMES_BACK_ON_FAILURE.matcher(statement.text()).find();
        }
        return "EXIT".equals(verb)
                && statement.text().toUpperCase(Locale.ROOT).contains("PROGRAM");
    }

    /** The options under which a failing CICS command returns control to the program. */
    private static final Pattern COMES_BACK_ON_FAILURE = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])(?:RESP2?\\s*\\(|NOHANDLE(?![\\p{L}\\p{N}-]))");

    /** The first word after EXEC CICS, which names the command. */
    private static final Pattern CICS_COMMAND =
            Pattern.compile("(?is)^\\s*EXEC\\s+CICS\\s+([\\p{L}\\p{N}-]+).*$");

    /**
     * Whether control can leave the end of the statements to what follows them: the rule the
     * graph's chains follow, for a reader that needs the answer without the graph, such as the
     * stored paragraph flow's fall-through rows. It gives the reachability the graph gives:
     * statements after the first one that cannot complete are unreachable, and control leaves
     * only through what precedes them. A statement that cannot complete is a terminator
     * ({@link #isTerminator}), a GO TO that {@link #fallsThrough(GoToStatement)} denies, an
     * exhaustive branch none of whose arms completes, or a PERFORM that runs its body once and
     * whose body cannot complete. A loop completes, and so does an empty list.
     */
    public static boolean canCompleteNormally(List<Statement> statements) {
        for (Statement statement : statements) {
            boolean completes = switch (statement) {
                case SimpleStatement simple -> !isTerminator(simple);
                case GoToStatement goTo -> fallsThrough(goTo);
                case CompoundStatement compound -> runsOnce(compound)
                        ? canCompleteNormally(compound.blocks().get(0).statements())
                        : compound.kind() == ControlKind.LOOP
                        || !isExhaustive(compound)
                        || compound.blocks().stream()
                                .anyMatch(block -> canCompleteNormally(block.statements()));
            };
            if (!completes) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a GO TO can go on to the next statement. One with a single target cannot; a
     * DEPENDING ON goes on when none of its targets is chosen.
     */
    public static boolean fallsThrough(GoToStatement goTo) {
        return goTo.targets().size() > 1 || goTo.dependingOn().isPresent();
    }

    /** Whether a loop is an inline PERFORM with no UNTIL, VARYING or TIMES: its condition is empty. */
    private static boolean runsOnce(CompoundStatement compound) {
        return compound.kind() == ControlKind.LOOP && compound.conditionText().isBlank();
    }

    /** Whether a branch takes one of its arms on every path: it has an ELSE or a WHEN OTHER. */
    private static boolean isExhaustive(CompoundStatement branch) {
        return branch.blocks().stream()
                .anyMatch(block -> "ELSE".equals(block.label()) || "OTHER".equals(block.label()));
    }

    /**
     * Whether the relation is the PERFORM statement itself with a UNTIL clause. The semantic
     * model does not decompose the clause, so this checks the statement text for UNTIL as a word
     * of its own; a procedure name such as WAIT-UNTIL-READY holds no clause.
     */
    private static boolean isUntilPerform(CfgNode node, PerformRelation perform) {
        return node.statement().orElseThrow() instanceof SimpleStatement simple
                && "PERFORM".equals(simple.verb()) && simple.range().equals(perform.range())
                && UNTIL.matcher(simple.text()).find();
    }

    private static final Pattern UNTIL =
            Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])UNTIL(?![\\p{L}\\p{N}-])");

    private CfgNode newNode(CfgNodeKind kind, Statement statement, String procedureName) {
        CfgNode node = new CfgNode(nextId++, kind, statement, procedureName);
        nodes.add(node);
        successors.put(node, new LinkedHashSet<>());
        return node;
    }

    private void addEdge(CfgNode from, CfgNode to) {
        successors.get(from).add(to);
    }

    private LinkedHashSet<CfgNode> successorsOf(CfgNode node) {
        return successors.get(node);
    }
}
