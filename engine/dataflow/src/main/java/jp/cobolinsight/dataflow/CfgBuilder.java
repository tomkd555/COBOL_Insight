package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.CfgNodeKind;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.ControlKind;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.PerformRelation;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 正規化意味モデルから制御フローグラフを構築する。段落・節の並び、IF/EVALUATEの分岐、
 * インラインPERFORMの反復、GO TOの分岐、STOP RUN・GOBACK・EXIT PROGRAMの終端、
 * 段落末尾の後続段落への流下、および段落PERFORM(THRU範囲を含む)の呼出・復帰を辺にする。
 *
 * <p>PERFORMの復帰辺は文脈非依存(範囲末尾から全呼出箇所の後続へ張る)であり、経路は
 * 過大近似になる。到達性の問い合わせ(検査有無・到達可能性)にはこの近似で足りる。
 */
public final class CfgBuilder {

    /** 手続き部の文リストの構築結果。first は先頭ノード(空リストでは null)、ends は末尾到達点。 */
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

        // 手続き名 → 定義順の索引(重複名は先勝ち)
        Map<String, Integer> indexByName = new LinkedHashMap<>();
        for (int i = 0; i < procedures.size(); i++) {
            indexByName.putIfAbsent(upper(procedures.get(i).name()), i);
        }

        // 入口 → 最初の実行文。段落末尾 → 次段落先頭(流下)。最終段落末尾 → 出口。
        addEdge(entry, effectiveEntry(chains, 0, exit));
        for (int i = 0; i < chains.size(); i++) {
            CfgNode next = effectiveEntry(chains, i + 1, exit);
            for (CfgNode end : chains.get(i).ends()) {
                addEdge(end, next);
            }
        }

        // PERFORM呼出・復帰辺を張る前に、PERFORM文ノードの逐次後続を控える(復帰先)。
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
                // PERFORM ... UNTIL は復帰後に自ノード(条件判定)へ戻る反復を表す
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

    /** index 以降で最初に文を持つ手続きの先頭ノード。無ければ出口。 */
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

    /** 1文のノードと辺を構築する。first は当該文の入口ノード、ends は実行後の到達点。 */
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
            // 単一飛び先の無条件GO TOは流下しない。DEPENDING ON(複数飛び先)は不成立時に流下する。
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
        // 分岐(IF/EVALUATE)。ELSE・WHEN OTHER を持たない分岐はどの枝も通らない経路があるため、
        // 分岐ノード自身を実行後の到達点に加えて後続へ流下させる。
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

    /** 実行を打ち切る文か。EXIT は EXIT PROGRAM のみ終端で、EXIT PARAGRAPH などは流下する。 */
    private static boolean isTerminator(SimpleStatement statement) {
        String verb = statement.verb();
        if ("STOP".equals(verb) || "GOBACK".equals(verb)) {
            return true;
        }
        return "EXIT".equals(verb)
                && statement.text().toUpperCase(Locale.ROOT).contains("PROGRAM");
    }

    /** 段落 PERFORM が UNTIL 句を持つか。意味モデルは句を分解しないため文テキストで判定する。 */
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
