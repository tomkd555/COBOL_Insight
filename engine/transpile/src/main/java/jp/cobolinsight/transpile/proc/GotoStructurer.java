package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.cfg.CfgNodeKind;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.ControlKind;
import jp.cobolinsight.engineapi.semantic.GoToStatement;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.dataflow.GotoNormalizer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * GO TO を含む段落を、手続き単位の部分制御フローグラフに写し、{@link GotoNormalizer} で可約化してから
 * 順次・分岐(if)・反復(while)の構造化制御へ還元する。段落=メソッドの対訳では PERFORM は呼出、GO TO は
 * 手続き内の制御移動である。よって部分 CFG には PERFORM 呼出・復帰辺を張らず(whole-program CFG の
 * 過大近似による偽の不可約性を避ける)、GO TO 辺・段落間の流下辺・段落内の逐次辺だけを張る。
 *
 * <p>対象段落の飛び先とその流下先を含む region に限定し、可約 CFG を支配木・後支配木に基づく構造解析で
 * 還元する。GO TO の飛び先段落は自前のメソッドとしても出力されるため、region への取り込みは複製となり、
 * 同一 COBOL 行が複数生成箇所へ対応する(1:N)。GotoNormalizer が不可約領域を複製した場合は
 * originalNodeId で複製と分かり、複製側の対応へ注記を付す。構造化できない形は null を返し、呼び手は
 * 従来の逐次走査(GO TO を注記付き非対訳とする)へ退避する。
 */
final class GotoStructurer {

    private static final String DUP_NOTE = "GO TO 構造化による複製(元の段落と重複)";

    private final List<Procedure> procedures;
    private final Map<String, Integer> indexByName;
    private final OperandParser parser;
    private final Function<Statement, List<ProcStmt>> leafConverter;

    GotoStructurer(List<Procedure> procedures, OperandParser parser,
            Function<Statement, List<ProcStmt>> leafConverter) {
        this.procedures = procedures;
        this.parser = parser;
        this.leafConverter = leafConverter;
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (int i = 0; i < procedures.size(); i++) {
            byName.putIfAbsent(upper(procedures.get(i).name()), i);
        }
        this.indexByName = byName;
    }

    /** procedure の文木のどこかに GO TO を含むか。 */
    static boolean containsGoTo(Procedure procedure) {
        for (Statement s : procedure.statements()) {
            if (containsGoTo(s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsGoTo(Statement s) {
        return switch (s) {
            case GoToStatement ignored -> true;
            case SimpleStatement ignored -> false;
            case CompoundStatement c -> {
                for (StatementBlock b : c.blocks()) {
                    for (Statement inner : b.statements()) {
                        if (containsGoTo(inner)) {
                            yield true;
                        }
                    }
                }
                yield false;
            }
        };
    }

    /** start を構造化制御へ還元した本体。GO TO を含まない、または還元できない場合は null。 */
    List<ProcStmt> structure(Procedure start) {
        if (!containsGoTo(start)) {
            return null;
        }
        try {
            RegionCfg region = buildRegion(start);
            ControlFlowGraph normalized = GotoNormalizer.normalize(region.cfg());
            if (!GotoNormalizer.isReducible(normalized)) {
                return null;
            }
            return new Analysis(normalized, region.condJumps(), start.name()).run();
        } catch (Unsupported unsupported) {
            return null;
        }
    }

    // ---- 部分 CFG の構築 ----

    private record RegionCfg(ControlFlowGraph cfg, Set<CfgNode> condJumps) {
    }

    /** 手続き文を GO TO 構造化のための「流れ要素」へ平坦化した1件。 */
    private sealed interface Item {

        Statement statement();

        /** 単文または GO TO を含まない複合文(不透過な葉)。terminal は STOP RUN 等の強い終端。 */
        record Leaf(Statement statement, boolean terminal) implements Item {
        }

        /** 無条件 GO TO(単一飛び先)。 */
        record Jump(GoToStatement statement, String target) implements Item {
        }

        /** IF 条件 GO TO(THEN が単一 GO TO のみ)。true で飛び先、false で流下。 */
        record CondJump(CompoundStatement statement, String target) implements Item {
        }
    }

    private RegionCfg buildRegion(Procedure start) {
        int startIdx = indexByName.get(upper(start.name()));
        List<Integer> region = computeRegion(startIdx);

        Map<Integer, List<Item>> itemsByPara = new LinkedHashMap<>();
        for (int idx : region) {
            List<Item> items = flatten(procedures.get(idx));
            if (items == null || items.isEmpty()) {
                throw new Unsupported();
            }
            itemsByPara.put(idx, items);
        }

        int nextId = 0;
        List<CfgNode> nodes = new ArrayList<>();
        CfgNode entry = new CfgNode(nextId++, CfgNodeKind.ENTRY, null, "");
        nodes.add(entry);
        Map<Integer, CfgNode> paraFirst = new LinkedHashMap<>();
        Map<Integer, List<CfgNode>> paraNodes = new LinkedHashMap<>();
        for (int idx : region) {
            List<CfgNode> pn = new ArrayList<>();
            for (Item item : itemsByPara.get(idx)) {
                CfgNode node = new CfgNode(nextId++, CfgNodeKind.STATEMENT, item.statement(),
                        procedures.get(idx).name());
                nodes.add(node);
                pn.add(node);
            }
            paraFirst.put(idx, pn.get(0));
            paraNodes.put(idx, pn);
        }
        CfgNode exit = new CfgNode(nextId++, CfgNodeKind.EXIT, null, "");
        nodes.add(exit);

        Map<CfgNode, List<CfgNode>> successors = new LinkedHashMap<>();
        for (CfgNode node : nodes) {
            successors.put(node, new ArrayList<>());
        }
        successors.get(entry).add(paraFirst.get(startIdx));

        Set<CfgNode> condJumps = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Integer> regionSet = new TreeSet<>(region);
        for (int idx : region) {
            List<Item> items = itemsByPara.get(idx);
            List<CfgNode> pn = paraNodes.get(idx);
            CfgNode fallThrough = fallThroughTarget(idx, startIdx, items, regionSet, paraFirst, exit);
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                CfgNode node = pn.get(i);
                CfgNode after = (i + 1 < items.size()) ? pn.get(i + 1) : fallThrough;
                switch (item) {
                    case Item.Leaf leaf -> successors.get(node).add(leaf.terminal() ? exit : after);
                    case Item.Jump jump -> successors.get(node).add(targetNode(jump.target(),
                            regionSet, paraFirst));
                    case Item.CondJump cond -> {
                        condJumps.add(node);
                        successors.get(node).add(targetNode(cond.target(), regionSet, paraFirst));
                        successors.get(node).add(after);
                    }
                }
            }
        }

        Map<Statement, CfgNode> byStatement = new IdentityHashMap<>();
        for (CfgNode node : nodes) {
            node.statement().ifPresent(statement -> byStatement.put(statement, node));
        }
        ControlFlowGraph cfg = new ControlFlowGraph(start.name(), nodes, entry, exit, successors,
                byStatement);
        return new RegionCfg(cfg, condJumps);
    }

    private CfgNode targetNode(String target, Set<Integer> regionSet, Map<Integer, CfgNode> paraFirst) {
        Integer idx = indexByName.get(upper(target));
        if (idx == null || !regionSet.contains(idx)) {
            throw new Unsupported();
        }
        return paraFirst.get(idx);
    }

    /** 段落末尾からの流下先。開始段落(PERFORM で呼ばれる)は末尾で復帰するため出口へ。 */
    private CfgNode fallThroughTarget(int idx, int startIdx, List<Item> items, Set<Integer> regionSet,
            Map<Integer, CfgNode> paraFirst, CfgNode exit) {
        if (idx == startIdx || !fallsThrough(items)) {
            return exit;
        }
        int next = idx + 1;
        return regionSet.contains(next) ? paraFirst.get(next) : exit;
    }

    private List<Integer> computeRegion(int startIdx) {
        Set<Integer> region = new TreeSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        region.add(startIdx);
        queue.add(startIdx);
        while (!queue.isEmpty()) {
            int p = queue.poll();
            List<Item> items = flatten(procedures.get(p));
            if (items == null) {
                throw new Unsupported();
            }
            for (Item item : items) {
                String target = targetOf(item);
                if (target != null) {
                    Integer idx = indexByName.get(upper(target));
                    if (idx == null) {
                        throw new Unsupported();
                    }
                    if (region.add(idx)) {
                        queue.add(idx);
                    }
                }
            }
            if (p != startIdx && fallsThrough(items)) {
                int next = p + 1;
                if (next < procedures.size() && region.add(next)) {
                    queue.add(next);
                }
            }
        }
        return new ArrayList<>(region);
    }

    private static String targetOf(Item item) {
        return switch (item) {
            case Item.Jump jump -> jump.target();
            case Item.CondJump cond -> cond.target();
            case Item.Leaf ignored -> null;
        };
    }

    /** 段落が末尾で次段落へ流下するか(無条件 GO TO・強い終端・EXIT 段落境界では流下しない)。 */
    private static boolean fallsThrough(List<Item> items) {
        Item last = items.get(items.size() - 1);
        return switch (last) {
            case Item.Jump ignored -> false;
            case Item.CondJump ignored -> true;
            case Item.Leaf leaf -> !leaf.terminal() && !isPlainExit(leaf.statement());
        };
    }

    /** procedure の文を流れ要素へ平坦化する。構造化対象外の GO TO 入れ子があれば null。 */
    private List<Item> flatten(Procedure procedure) {
        List<Item> items = new ArrayList<>();
        for (Statement s : procedure.statements()) {
            switch (s) {
                case GoToStatement g -> {
                    if (g.targets().size() != 1 || g.dependingOn().isPresent()) {
                        return null;
                    }
                    items.add(new Item.Jump(g, g.targets().get(0)));
                }
                case CompoundStatement c -> {
                    if (!containsGoTo(c)) {
                        items.add(new Item.Leaf(c, false));
                    } else {
                        String target = condJumpTarget(c);
                        if (target == null) {
                            return null;
                        }
                        items.add(new Item.CondJump(c, target));
                    }
                }
                case SimpleStatement ss -> items.add(new Item.Leaf(ss, isHardTerminator(ss)));
            }
        }
        return items;
    }

    /** IF 条件 GO TO 形(THEN が単一 GO TO・ELSE なし)なら飛び先、そうでなければ null。 */
    private static String condJumpTarget(CompoundStatement c) {
        if (c.kind() != ControlKind.BRANCH || c.blocks().size() != 1) {
            return null;
        }
        StatementBlock block = c.blocks().get(0);
        if (!block.label().equalsIgnoreCase("THEN") || block.statements().size() != 1) {
            return null;
        }
        if (!(block.statements().get(0) instanceof GoToStatement g)) {
            return null;
        }
        if (g.targets().size() != 1 || g.dependingOn().isPresent()) {
            return null;
        }
        return g.targets().get(0);
    }

    private static boolean isHardTerminator(SimpleStatement s) {
        String verb = s.verb().toUpperCase(Locale.ROOT);
        if (verb.equals("STOP") || verb.equals("GOBACK")) {
            return true;
        }
        return verb.equals("EXIT") && s.text().toUpperCase(Locale.ROOT).contains("PROGRAM");
    }

    private static boolean isPlainExit(Statement s) {
        return s instanceof SimpleStatement ss && ss.verb().equalsIgnoreCase("EXIT")
                && !ss.text().toUpperCase(Locale.ROOT).contains("PROGRAM");
    }

    // ---- 構造解析(可約 CFG → 順次/if/while)----

    private final class Analysis {

        private final ControlFlowGraph cfg;
        private final Set<CfgNode> condJumps;
        private final String startPara;
        private final Dom dom;
        private final Dom postDom;
        private final Set<CfgNode> loopHeaders = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<CfgNode> backEdgeSources = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<CfgNode, Set<CfgNode>> loopBodies = new IdentityHashMap<>();
        private final Set<CfgNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        Analysis(ControlFlowGraph cfg, Set<CfgNode> condJumps, String startPara) {
            this.cfg = cfg;
            this.condJumps = condJumps;
            this.startPara = startPara;
            this.dom = Dom.build(cfg.entry(), cfg::successors, cfg::predecessors);
            this.postDom = Dom.build(cfg.exit(), cfg::predecessors, cfg::successors);
        }

        List<ProcStmt> run() {
            for (CfgNode node : cfg.nodes()) {
                for (CfgNode succ : cfg.successors(node)) {
                    if (dom.dominates(succ, node)) {
                        backEdgeSources.add(node);
                        loopHeaders.add(succ);
                        loopBodies.computeIfAbsent(succ, h -> Collections.newSetFromMap(
                                new IdentityHashMap<>())).addAll(naturalLoop(succ, node));
                    }
                }
            }
            List<CfgNode> start = cfg.successors(cfg.entry());
            if (start.isEmpty()) {
                return List.of();
            }
            return structure(start.get(0), cfg.exit());
        }

        private List<ProcStmt> structure(CfgNode from, CfgNode stop) {
            List<ProcStmt> out = new ArrayList<>();
            CfgNode node = from;
            while (node != null && node != stop && node != cfg.exit() && !visited.contains(node)) {
                if (loopHeaders.contains(node)) {
                    LoopResult loop = buildLoop(node);
                    out.add(loop.loop());
                    node = loop.exit();
                    continue;
                }
                visited.add(node);
                if (condJumps.contains(node)) {
                    CfgNode follow = postDom.immediate(node);
                    if (follow == null) {
                        throw new Unsupported();
                    }
                    out.add(buildBranch(node, follow));
                    node = follow;
                } else {
                    out.addAll(leafProcStmts(node));
                    List<CfgNode> succ = cfg.successors(node);
                    node = succ.isEmpty() ? null : succ.get(0);
                }
            }
            return out;
        }

        private ProcStmt buildBranch(CfgNode node, CfgNode follow) {
            CompoundStatement c = (CompoundStatement) node.statement().orElseThrow();
            PCond cond = parser.parseBranchCondition(c);
            List<CfgNode> succ = cfg.successors(node);
            List<ProcStmt> thenArm = structure(succ.get(0), follow);
            List<ProcStmt> elseArm = structure(succ.get(1), follow);
            String note = branchNote("条件付き GO TO を if へ構造化", cond);
            if (thenArm.isEmpty() && !elseArm.isEmpty()) {
                ProcStmt.Arm arm = new ProcStmt.Arm(new PCond.Negate(cond), elseArm);
                return new ProcStmt.Branch(List.of(arm), List.of(), c.range(), note);
            }
            return new ProcStmt.Branch(List.of(new ProcStmt.Arm(cond, thenArm)), elseArm, c.range(),
                    note);
        }

        private LoopResult buildLoop(CfgNode header) {
            visited.add(header);
            if (!condJumps.contains(header)) {
                throw new Unsupported();
            }
            CompoundStatement c = (CompoundStatement) header.statement().orElseThrow();
            PCond cond = parser.parseBranchCondition(c);
            Set<CfgNode> body = loopBodies.getOrDefault(header, Set.of());
            List<CfgNode> succ = cfg.successors(header);
            boolean trueInLoop = body.contains(succ.get(0));
            boolean falseInLoop = body.contains(succ.get(1));
            if (trueInLoop == falseInLoop) {
                throw new Unsupported();
            }
            CfgNode inLoop = trueInLoop ? succ.get(0) : succ.get(1);
            CfgNode exit = trueInLoop ? succ.get(1) : succ.get(0);
            PCond until = trueInLoop ? new PCond.Negate(cond) : cond;
            List<ProcStmt> loopBody = structure(inLoop, header);
            String note = branchNote("後方 GO TO を while ループへ構造化", cond);
            ProcStmt loop = new ProcStmt.Loop(until, loopBody, null, null, null, c.range(), note);
            return new LoopResult(loop, exit);
        }

        private String branchNote(String base, PCond cond) {
            return cond instanceof PCond.Raw ? base + " / 条件を直訳できない" : base;
        }

        private List<ProcStmt> leafProcStmts(CfgNode node) {
            Statement s = node.statement().orElseThrow();
            if (s instanceof GoToStatement g) {
                String targets = String.join(" ", g.targets());
                String note = backEdgeSources.contains(node)
                        ? "GO TO " + targets + " を while ループ先頭への戻りへ構造化"
                        : "GO TO " + targets + " を以降の順次実行へ構造化";
                return List.of(new ProcStmt.Untranslated(List.of("GO TO " + targets), g.range(), note));
            }
            List<ProcStmt> base = leafConverter.apply(s);
            boolean inlined = node.originalNodeId().isPresent()
                    || !node.procedureName().equalsIgnoreCase(startPara);
            if (!inlined) {
                return base;
            }
            List<ProcStmt> renoted = new ArrayList<>(base.size());
            for (ProcStmt ps : base) {
                renoted.add(reNote(ps, DUP_NOTE));
            }
            return renoted;
        }

        private Set<CfgNode> naturalLoop(CfgNode header, CfgNode backSource) {
            Set<CfgNode> body = Collections.newSetFromMap(new IdentityHashMap<>());
            body.add(header);
            Deque<CfgNode> work = new ArrayDeque<>();
            if (body.add(backSource)) {
                work.push(backSource);
            }
            while (!work.isEmpty()) {
                CfgNode x = work.pop();
                for (CfgNode pred : cfg.predecessors(x)) {
                    if (body.add(pred)) {
                        work.push(pred);
                    }
                }
            }
            return body;
        }
    }

    private record LoopResult(ProcStmt loop, CfgNode exit) {
    }

    private static ProcStmt reNote(ProcStmt ps, String extra) {
        return switch (ps) {
            case ProcStmt.Assign s -> new ProcStmt.Assign(s.target(), s.value(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.Branch s -> new ProcStmt.Branch(s.arms(), s.elseBody(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.Loop s -> new ProcStmt.Loop(s.until(), s.body(), s.varyingVar(),
                    s.varyingInit(), s.varyingStep(), s.range(), combine(s.note(), extra));
            case ProcStmt.PerformCall s -> new ProcStmt.PerformCall(s.methodName(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.PerformTimes s -> new ProcStmt.PerformTimes(s.methodName(), s.count(),
                    s.range(), combine(s.note(), extra));
            case ProcStmt.PerformThru s -> new ProcStmt.PerformThru(s.methodNames(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.Display s -> new ProcStmt.Display(s.operands(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.CallProgram s -> new ProcStmt.CallProgram(s.target(), s.argDescriptors(),
                    s.range(), combine(s.note(), extra));
            case ProcStmt.Return s -> new ProcStmt.Return(s.verb(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.NoOp s -> new ProcStmt.NoOp(s.verb(), s.range(), combine(s.note(), extra));
            case ProcStmt.Untranslated s -> new ProcStmt.Untranslated(s.cobolTextLines(), s.range(),
                    combine(s.note(), extra));
            case ProcStmt.EmbeddedStub s -> new ProcStmt.EmbeddedStub(s.command(),
                    s.cobolTextLines(), s.range(), combine(s.note(), extra));
        };
    }

    private static String combine(String note, String extra) {
        return note.isEmpty() ? extra : note + " / " + extra;
    }

    private static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    /** 構造化対象外の形(未対応の GO TO 入れ子・非可約・後支配不能等)を検知して退避する内部シグナル。 */
    private static final class Unsupported extends RuntimeException {
        Unsupported() {
            super(null, null, false, false);
        }
    }
}
