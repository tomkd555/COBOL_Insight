package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Traversal and search helpers shared by control-flow analysis rules. Stateless. */
public final class CfgSupport {

    private CfgSupport() {
    }

    static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** Pre-order traversal, in definition order, of statements and the nested bodies of their compound statements. */
    static void walk(List<Statement> statements, Consumer<Statement> visitor) {
        for (Statement statement : statements) {
            visitor.accept(statement);
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    walk(block.statements(), visitor);
                }
            }
        }
    }

    /** Returns the procedure that contains target (by instance identity, including nested statements). Null if none does. */
    static Procedure containingProcedure(CobolSemanticModel model, Statement target) {
        for (Procedure procedure : model.procedures()) {
            if (containsStatement(procedure.statements(), target)) {
                return procedure;
            }
        }
        return null;
    }

    private static boolean containsStatement(List<Statement> statements, Statement target) {
        for (Statement statement : statements) {
            if (statement == target) {
                return true;
            }
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    if (containsStatement(block.statements(), target)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Performs a forward BFS over successor edges starting from start. When a boundary node is
     * reached, the search stops there (its successors are not followed, and check is not
     * evaluated on it). Returns true if any other reached node satisfies check. start itself is
     * not evaluated; traversal begins from its successors.
     */
    public static boolean forwardHasMatch(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary, Predicate<CfgNode> check) {
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode next : cfg.successors(start)) {
            if (visited.add(next)) {
                queue.addLast(next);
            }
        }
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            if (boundary.test(node)) {
                continue;
            }
            if (check.test(node)) {
                return true;
            }
            for (CfgNode next : cfg.successors(node)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return false;
    }
    /**
     * The first boundary node a forward walk from {@code start} reaches, in breadth-first order.
     * Empty when every path from {@code start} leaves the graph without meeting one.
     */
    static Optional<CfgNode> firstBoundary(ControlFlowGraph cfg, CfgNode start,
            Predicate<CfgNode> boundary) {
        Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        for (CfgNode next : cfg.successors(start)) {
            if (visited.add(next)) {
                queue.addLast(next);
            }
        }
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            if (boundary.test(node)) {
                return Optional.of(node);
            }
            for (CfgNode next : cfg.successors(node)) {
                if (visited.add(next)) {
                    queue.addLast(next);
                }
            }
        }
        return Optional.empty();
    }

    /** A code flow of one step per position, so a finding can point at the places it is about. */
    static CodeFlow flow(CodeFlowStep... steps) {
        return new CodeFlow(List.of(steps));
    }

    public static CodeFlowStep step(String file, int line, String message) {
        return new CodeFlowStep(new SourcePosition(file, line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET), message);
    }
}
