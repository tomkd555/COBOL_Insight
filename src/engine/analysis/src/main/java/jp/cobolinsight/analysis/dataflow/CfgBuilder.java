package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.GoToStatement;
import jp.cobolinsight.core.semantic.PerformRelation;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourceRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    private record PendingGoTo(CfgNode node, List<String> targets) {
    }

    private final CobolSemanticModel model;
    private final List<CfgNode> nodes = new ArrayList<>();
    private final Map<CfgNode, LinkedHashSet<CfgNode>> successors = new LinkedHashMap<>();
    private final Map<Statement, CfgNode> byStatement = new IdentityHashMap<>();
    private final List<CfgNode> terminators = new ArrayList<>();
    private final List<PendingGoTo> gotos = new ArrayList<>();
    private final Map<SourceRange, CfgNode> performNodeByRange = new HashMap<>();
    private int nextId;

    private CfgBuilder(CobolSemanticModel model) {
        this.model = model;
    }

    public static ControlFlowGraph build(CobolSemanticModel model) {
        return new CfgBuilder(model).run();
    }

    private ControlFlowGraph run() {
        CfgNode entry = newNode(CfgNodeKind.ENTRY, null, "");
        List<Procedure> procedures = model.procedures();
        List<Chain> chains = new ArrayList<>();
        for (Procedure procedure : procedures) {
            chains.add(buildSeq(procedure.statements(), procedure.name()));
        }
        CfgNode exit = newNode(CfgNodeKind.EXIT, null, "");

        // Procedure name -> index in definition order (first occurrence wins for duplicate names)
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int i = 0; i < procedures.size(); i++) {
            indexByName.putIfAbsent(upper(procedures.get(i).name()), i);
        }

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
        for (CfgNode node : performNodeByRange.values()) {
            returnPointsByPerform.put(node, List.copyOf(successorsOf(node)));
        }

        for (PendingGoTo pending : gotos) {
            for (String target : pending.targets()) {
                Integer index = indexByName.get(upper(target));
                if (index != null) {
                    addEdge(pending.node(), effectiveEntry(chains, index, exit));
                }
            }
        }

        for (PerformRelation perform : model.performs()) {
            CfgNode node = performNodeByRange.get(perform.range());
            if (node == null) {
                continue;
            }
            Integer target = indexByName.get(upper(perform.targetProcedure()));
            Integer thru = perform.thruProcedure().map(name -> indexByName.get(upper(name)))
                    .orElse(target);
            if (target == null || thru == null || thru < target) {
                continue;
            }
            addEdge(node, effectiveEntry(chains, target, exit));
            List<CfgNode> returnPoints = new ArrayList<>(returnPointsByPerform.get(node));
            if (isUntilPerform(node)) {
                // PERFORM ... UNTIL represents an iteration that, after returning, loops back
                // to its own node (the condition check)
                returnPoints.add(node);
            }
            for (CfgNode end : chains.get(thru).ends()) {
                for (CfgNode point : returnPoints) {
                    addEdge(end, point);
                }
            }
        }

        for (CfgNode terminator : terminators) {
            addEdge(terminator, exit);
        }

        Map<CfgNode, List<CfgNode>> edges = new LinkedHashMap<>();
        successors.forEach((node, set) -> edges.put(node, List.copyOf(set)));
        return new ControlFlowGraph(model.programId(), nodes, entry, exit, edges, byStatement);
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
            if ("PERFORM".equals(simple.verb())) {
                performNodeByRange.put(simple.range(), node);
            }
            return new Chain(node, List.of(node));
        }
        if (statement instanceof GoToStatement goTo) {
            if (!goTo.targets().isEmpty()) {
                gotos.add(new PendingGoTo(node, goTo.targets()));
            }
            // An unconditional GO TO with a single target does not fall through. DEPENDING ON
            // (multiple targets) falls through when none of the conditions is met.
            boolean conditional = goTo.targets().size() > 1 || goTo.dependingOn().isPresent();
            return new Chain(node, conditional ? List.of(node) : List.of());
        }
        CompoundStatement compound = (CompoundStatement) statement;
        List<CfgNode> ends = new ArrayList<>();
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
        boolean exhaustive = false;
        for (StatementBlock block : compound.blocks()) {
            if ("ELSE".equals(block.label()) || "OTHER".equals(block.label())) {
                exhaustive = true;
            }
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
     * Whether this statement terminates execution. For EXIT, only EXIT PROGRAM is a terminator;
     * EXIT PARAGRAPH and the like fall through.
     *
     * <p>Made public because the scan that derives flow between paragraphs needs the same
     * judgment. Encoding the same COBOL semantics in two places risks fixing only one of them.
     */
    public static boolean isTerminator(SimpleStatement statement) {
        String verb = statement.verb();
        if ("STOP".equals(verb) || "GOBACK".equals(verb)) {
            return true;
        }
        return "EXIT".equals(verb)
                && statement.text().toUpperCase(Locale.ROOT).contains("PROGRAM");
    }

    /** Whether a paragraph PERFORM has a UNTIL clause. The semantic model does not decompose the clause, so this checks the statement text. */
    private boolean isUntilPerform(CfgNode node) {
        return node.statement().orElseThrow() instanceof SimpleStatement simple
                && simple.text().toUpperCase(Locale.ROOT).contains("UNTIL");
    }

    private CfgNode newNode(CfgNodeKind kind, Statement statement, String procedureName) {
        CfgNode node = new CfgNode(nextId++, kind, statement, procedureName);
        nodes.add(node);
        successors.put(node, new LinkedHashSet<>());
        if (statement != null) {
            byStatement.put(statement, node);
        }
        return node;
    }

    private void addEdge(CfgNode from, CfgNode to) {
        successors.get(from).add(to);
    }

    private LinkedHashSet<CfgNode> successorsOf(CfgNode node) {
        return successors.get(node);
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
