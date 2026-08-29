package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 制御フロー解析のルールが共有する走査・探索の補助。状態を持たない。 */
final class CfgSupport {

    private CfgSupport() {
    }

    static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    /** 文とその複合文の入れ子本体を、定義順に前順走査する。 */
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

    /** target(同一インスタンス)を(入れ子を含め)含む手続きを返す。無ければ null。 */
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
     * start から後続辺を前方BFSする。boundary ノードに達したらそこで打ち切り(後続を辿らず、
     * check の判定もしない)。それ以外の到達ノードに check を満たすものがあれば true。
     * start 自身は判定せず、その後続から辿る。
     */
    static boolean forwardHasMatch(ControlFlowGraph cfg, CfgNode start,
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
}
