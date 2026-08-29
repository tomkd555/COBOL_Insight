package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.CfgNodeKind;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.dataflow.ValueInterval;
import jp.cobolinsight.core.picture.PictureType;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.ControlKind;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 前進データフローで各変数の整数区間 {@link ValueInterval} を追跡する区間値域解析。束は変数ごとの
 * 区間の直積、合流は区間包(hull)。転送関数は MOVE/SET/ADD/SUBTRACT/MULTIPLY/COMPUTE と
 * PERFORM VARYING の制御変数の歩進を区間演算で求め、追跡できない代入・外部入力・PIC 不明の項目は
 * 非有界とする。
 *
 * <p>区間束は無限昇鎖のため、ループ後行辺の合流点(後退辺の終端ノード)で widening を適用して有限回で
 * 収束させる。widening 後に PERFORM UNTIL の継続条件から narrowing で上下端を絞り精度を回復する。
 * 絞った状態を流すのは反復本体へ向かう辺に限り、ループ脱出辺へは継続条件の否定で絞った状態を流す。
 * 分岐(IF/EVALUATE)は true/false 辺の区別が CFG に無いため、区間の絞り込みを行わず過大近似のまま
 * 残す。
 *
 * <p>段落 PERFORM VARYING は文テキストに FROM/BY/UNTIL が残るため制御変数を初期値から歩進で追跡する。
 * インライン PERFORM は VARYING/FROM/BY 句が conditionText へ残らず UNTIL 条件のみが得られるため、
 * 制御変数の範囲は UNTIL 境界からのみ絞る(昇順ループの下端は 1 と仮定する近似)。
 */
final class IntervalAnalysis {

    /** ループ制御句(VARYING 歩進と UNTIL 継続条件)。header ノードごとに事前解析して保持する。 */
    private record LoopClause(String varyingVar, String fromToken, String byToken, Comparison until) {
    }

    private enum Rel {
        GT, GE, LT, LE, EQ, NE, OTHER
    }

    private record Comparison(String var, Rel rel, String boundToken) {
    }

    private final Map<String, ValueInterval> picBounds = new LinkedHashMap<>();
    private final Map<String, ValueInterval> valueSeeds = new LinkedHashMap<>();

    private IntervalAnalysis(CobolSemanticModel model) {
        for (DataItem item : model.dataItems()) {
            collect(item);
        }
    }

    /** 各ノード入口の区間状態(変数名→区間)を返す。CfgNode 同一性で索引化する。 */
    static Map<CfgNode, Map<String, ValueInterval>> run(ControlFlowGraph cfg,
            CobolSemanticModel model) {
        return new IntervalAnalysis(model).solve(cfg);
    }

    // ---- データ項目メタから PIC 境界と VALUE 初期値を収集 ----

    private void collect(DataItem item) {
        String name = item.name().toUpperCase(Locale.ROOT);
        picBound(item).ifPresent(interval -> picBounds.putIfAbsent(name, interval));
        valueSeed(item).ifPresent(interval -> valueSeeds.putIfAbsent(name, interval));
        for (DataItem child : item.children()) {
            collect(child);
        }
    }

    private static java.util.Optional<ValueInterval> picBound(DataItem item) {
        if (item.picture().isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            PictureType pt = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            if (!pt.isNumeric()) {
                return java.util.Optional.empty();
            }
            int digits = pt.integerDigits();
            // 19桁以上の整数部は long で表せる範囲を超えるため、境界を持たない(非有界)扱いにする。
            if (digits <= 0 || digits > 18) {
                return java.util.Optional.empty();
            }
            long mag = pow10(digits) - 1;
            long lo = pt.signed() ? -mag : 0;
            return java.util.Optional.of(ValueInterval.of(lo, mag));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static java.util.Optional<ValueInterval> valueSeed(DataItem item) {
        if (item.picture().isEmpty() || item.value().isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            PictureType pt = PictureType.parse(item.picture().get(), item.usage().orElse(null));
            if (!pt.isNumeric()) {
                return java.util.Optional.empty();
            }
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
        Long v = parseLiteral(item.value().get());
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(ValueInterval.point(v));
    }

    private static long pow10(int n) {
        long r = 1;
        for (int i = 0; i < n; i++) {
            r *= 10;
        }
        return r;
    }

    // ---- worklist 本体(widening + narrowing) ----

    private Map<CfgNode, Map<String, ValueInterval>> solve(ControlFlowGraph cfg) {
        Map<CfgNode, LoopClause> loopClauses = loopClauses(cfg);
        Set<CfgNode> wideningNodes = wideningNodes(cfg);
        Map<CfgNode, Set<CfgNode>> loopExits = loopExitTargets(cfg, loopClauses);

        Map<CfgNode, Map<String, ValueInterval>> flowIn = new IdentityHashMap<>();
        Map<CfgNode, Map<String, ValueInterval>> flowOut = new IdentityHashMap<>();
        // ループ脱出辺へ流す状態。継続条件で絞った flowOut と別に持つ。
        Map<CfgNode, Map<String, ValueInterval>> exitOut = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            flowIn.put(node, new LinkedHashMap<>());
            flowOut.put(node, new LinkedHashMap<>());
            exitOut.put(node, new LinkedHashMap<>());
        }

        Deque<CfgNode> work = new ArrayDeque<>(cfg.nodes());
        Set<CfgNode> queued = new LinkedHashSet<>(cfg.nodes());
        long pops = 0;
        // 取り出し回数の上限は暴走を防ぐ保険である。widening が効いていれば各ノードの状態は有限回で
        // 安定するため、上限に達するのは widening 点の同定に不備がある場合だけである。
        long cap = Math.max(200_000L, (long) cfg.nodes().size() * cfg.nodes().size());
        while (!work.isEmpty()) {
            if (++pops > cap) {
                throw new IllegalStateException("区間値域解析が収束しない(widening 不備の疑い): "
                        + cfg.programId());
            }
            CfgNode node = work.pollFirst();
            queued.remove(node);

            Map<String, ValueInterval> newIn = new LinkedHashMap<>();
            if (node == cfg.entry()) {
                newIn.putAll(valueSeeds);
            }
            for (CfgNode pred : cfg.predecessors(node)) {
                joinInto(newIn, loopExits.get(pred).contains(node)
                        ? exitOut.get(pred) : flowOut.get(pred));
            }
            flowIn.put(node, newIn);

            LoopClause clause = loopClauses.get(node);
            Map<String, ValueInterval> cand = transfer(node, newIn, clause);
            if (wideningNodes.contains(node)) {
                cand = widenState(flowOut.get(node), cand);
            }
            Map<String, ValueInterval> exitCand = cand;
            if (!loopExits.get(node).isEmpty()) {
                exitCand = new LinkedHashMap<>(cand);
                narrowExit(exitCand, newIn, clause);
            }
            narrow(cand, clause);

            if (!cand.equals(flowOut.get(node)) || !exitCand.equals(exitOut.get(node))) {
                flowOut.put(node, cand);
                exitOut.put(node, exitCand);
                for (CfgNode succ : cfg.successors(node)) {
                    if (queued.add(succ)) {
                        work.addLast(succ);
                    }
                }
            }
        }
        return flowIn;
    }

    private static void joinInto(Map<String, ValueInterval> acc, Map<String, ValueInterval> other) {
        for (Map.Entry<String, ValueInterval> e : other.entrySet()) {
            acc.merge(e.getKey(), e.getValue(), Intervals::hull);
        }
    }

    private static Map<String, ValueInterval> widenState(Map<String, ValueInterval> old,
            Map<String, ValueInterval> next) {
        Map<String, ValueInterval> out = new LinkedHashMap<>();
        for (Map.Entry<String, ValueInterval> e : next.entrySet()) {
            ValueInterval ov = old.get(e.getKey());
            out.put(e.getKey(), ov == null ? e.getValue() : Intervals.widen(ov, e.getValue()));
        }
        return out;
    }

    /** ループ header の継続条件から制御/被検査変数を絞る。widening 後に適用して精度を回復する。 */
    private void narrow(Map<String, ValueInterval> state, LoopClause clause) {
        if (clause == null || clause.until() == null) {
            return;
        }
        Comparison cond = clause.until();
        if (cond.var() == null || !state.containsKey(cond.var())) {
            return;
        }
        ValueInterval bound = resolve(cond.boundToken(), state);
        ValueInterval v = state.get(cond.var());
        // UNTIL が真で反復を止めるため、ループ本体では継続条件(UNTIL の否定)が成り立つ。
        switch (cond.rel()) {
            case GT -> {
                if (!bound.hiUnbounded()) {
                    state.put(cond.var(), Intervals.capHi(v, bound.hi()));
                }
            }
            case GE -> {
                if (!bound.hiUnbounded()) {
                    state.put(cond.var(), Intervals.capHi(v, bound.hi() - 1));
                }
            }
            case LT -> {
                if (!bound.loUnbounded()) {
                    state.put(cond.var(), Intervals.capLo(v, bound.lo()));
                }
            }
            case LE -> {
                if (!bound.loUnbounded()) {
                    state.put(cond.var(), Intervals.capLo(v, bound.lo() + 1));
                }
            }
            default -> {
            }
        }
    }

    /**
     * ループ脱出辺へ流す状態を、継続条件の否定(= UNTIL 条件の成立)で絞る。反復は UNTIL が真に
     * なって初めて止まるため、脱出後の制御変数は境界の外側にある。継続用に絞った区間をそのまま
     * 流すと、脱出後の参照で区間が実際より狭くなり、範囲超過を見逃す。
     */
    private void narrowExit(Map<String, ValueInterval> state, Map<String, ValueInterval> in,
            LoopClause clause) {
        if (clause == null || clause.until() == null || clause.until().var() == null) {
            return;
        }
        Comparison cond = clause.until();
        ValueInterval bound = resolve(cond.boundToken(), in);
        ValueInterval v = resolve(cond.var(), in);
        switch (cond.rel()) {
            case GT -> {
                if (!bound.hiUnbounded()) {
                    state.put(cond.var(), Intervals.capLo(v, bound.hi() + 1));
                }
            }
            case GE -> {
                if (!bound.hiUnbounded()) {
                    state.put(cond.var(), Intervals.capLo(v, bound.hi()));
                }
            }
            case LT -> {
                if (!bound.loUnbounded()) {
                    state.put(cond.var(), Intervals.capHi(v, bound.lo() - 1));
                }
            }
            case LE -> {
                if (!bound.loUnbounded()) {
                    state.put(cond.var(), Intervals.capHi(v, bound.lo()));
                }
            }
            default -> {
            }
        }
    }

    /**
     * ループ header ごとの脱出先。UNTIL を持つノードから出る辺のうち、自ノードへ戻ってこない
     * 先を脱出辺とみなす。戻ってくる先は反復の本体である。
     */
    private static Map<CfgNode, Set<CfgNode>> loopExitTargets(ControlFlowGraph cfg,
            Map<CfgNode, LoopClause> loopClauses) {
        Map<CfgNode, Set<CfgNode>> exits = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            Set<CfgNode> targets = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            LoopClause clause = loopClauses.get(node);
            if (clause != null && clause.until() != null && clause.until().var() != null) {
                for (CfgNode succ : cfg.successors(node)) {
                    if (!forwardReach(cfg, succ).contains(node)) {
                        targets.add(succ);
                    }
                }
            }
            exits.put(node, targets);
        }
        return exits;
    }

    // ---- 転送関数 ----

    private Map<String, ValueInterval> transfer(CfgNode node, Map<String, ValueInterval> in,
            LoopClause clause) {
        Map<String, ValueInterval> out = new LinkedHashMap<>(in);
        if (node.kind() != CfgNodeKind.STATEMENT) {
            return out;
        }
        Statement statement = node.statement().orElseThrow();
        if (statement instanceof CompoundStatement compound) {
            if (compound.kind() == ControlKind.LOOP) {
                inlineLoopControl(clause, in, out);
            }
            return out;
        }
        if (!(statement instanceof SimpleStatement simple)) {
            return out;
        }
        String verb = simple.verb().toUpperCase(Locale.ROOT);
        String u = clean(simple.text());
        switch (verb) {
            case "MOVE" -> move(u, in, out);
            case "SET" -> set(u, in, out);
            case "ADD" -> add(u, in, out);
            case "SUBTRACT" -> subtract(u, in, out);
            case "MULTIPLY" -> multiply(u, in, out);
            case "COMPUTE" -> compute(u, in, out);
            case "INITIALIZE" -> initialize(u, out);
            case "PERFORM" -> applyVarying(clause, in, out);
            default -> defsToUnbounded(statement, out);
        }
        return out;
    }

    private void applyVarying(LoopClause clause, Map<String, ValueInterval> in,
            Map<String, ValueInterval> out) {
        if (clause == null || clause.varyingVar() == null) {
            return;
        }
        ValueInterval from = resolve(clause.fromToken(), in);
        ValueInterval by = clause.byToken() == null ? ValueInterval.point(1)
                : resolve(clause.byToken(), in);
        ValueInterval prev = in.get(clause.varyingVar());
        ValueInterval candidate = prev == null ? from
                : Intervals.hull(from, Intervals.add(prev, by));
        out.put(clause.varyingVar(), candidate);
    }

    /**
     * インライン PERFORM の制御変数を UNTIL 継続条件から絞る。パーサーは inline PERFORM の
     * VARYING/FROM/BY 句を conditionText へ残さず UNTIL 条件のみを渡すため、初期値・歩進が不明で
     * ある。昇順ループ(&gt;/&gt;=)は VARYING の常用形に合わせ下端を 1 と仮定し、上端を条件境界で絞る。
     *
     * <p>ループ突入前の区間とは合流させない。反復の本体は継続条件が成り立つときにしか実行されず、
     * 突入前の値(VALUE ZERO の 0 など)はそこに現れない。合流させると仮定した下端 1 が失われ、
     * 表の添字が 0 以下になり得るという結論だけが残る。
     */
    private void inlineLoopControl(LoopClause clause, Map<String, ValueInterval> in,
            Map<String, ValueInterval> out) {
        if (clause == null || clause.until() == null || clause.until().var() == null) {
            return;
        }
        Comparison cond = clause.until();
        ValueInterval bound = resolve(cond.boundToken(), in);
        Long lo;
        Long hi;
        switch (cond.rel()) {
            case GT -> {
                lo = 1L;
                hi = bound.hiUnbounded() ? null : bound.hi();
            }
            case GE -> {
                lo = 1L;
                hi = bound.hiUnbounded() ? null : bound.hi() - 1;
            }
            case LT -> {
                lo = bound.loUnbounded() ? null : bound.lo();
                hi = null;
            }
            case LE -> {
                lo = bound.loUnbounded() ? null : bound.lo() + 1;
                hi = null;
            }
            default -> {
                return;
            }
        }
        out.put(cond.var(), Intervals.make(lo, hi));
    }

    private void move(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        int to = kw(u, "TO");
        if (to < 0) {
            return;
        }
        ValueInterval src = firstOperand(u.substring(0, to), in);
        for (String dst : topLevelNames(tail(u, to, "TO"))) {
            out.put(dst, src);
        }
    }

    private void set(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        int up = kw(u, "UP");
        int down = kw(u, "DOWN");
        if (up >= 0 || down >= 0) {
            boolean isUp = up >= 0;
            int conn = isUp ? up : down;
            int by = kw(u, "BY");
            String amtRegion = by >= 0 ? tail(u, by, "BY") : tail(u, conn, isUp ? "UP" : "DOWN");
            ValueInterval amt = firstOperand(amtRegion, in);
            for (String name : topLevelNames(u.substring(0, conn))) {
                ValueInterval cur = resolve(name, in);
                out.put(name, isUp ? Intervals.add(cur, amt) : Intervals.sub(cur, amt));
            }
            return;
        }
        int to = kw(u, "TO");
        if (to < 0) {
            return;
        }
        ValueInterval value = firstOperand(tail(u, to, "TO"), in);
        for (String name : topLevelNames(u.substring(0, to))) {
            out.put(name, value);
        }
    }

    private void add(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-ADD ");
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            int to = kw(body, "TO");
            String addendRegion = to >= 0 && to < giving ? body.substring(0, to)
                    : body.substring(0, giving);
            ValueInterval sum = sumOperands(addendRegion, in);
            if (to >= 0 && to < giving) {
                sum = Intervals.add(sum, sumOperands(body.substring(to, giving), in));
            }
            for (String dst : topLevelNames(tail(body, giving, "GIVING"))) {
                out.put(dst, sum);
            }
            return;
        }
        int to = kw(body, "TO");
        if (to < 0) {
            return;
        }
        ValueInterval addends = sumOperands(body.substring(0, to), in);
        for (String dst : topLevelNames(tail(body, to, "TO"))) {
            out.put(dst, Intervals.add(resolve(dst, in), addends));
        }
    }

    private void subtract(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-SUBTRACT ");
        int from = kw(body, "FROM");
        if (from < 0) {
            return;
        }
        ValueInterval subtrahend = sumOperands(body.substring(0, from), in);
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            ValueInterval minuend = firstOperand(body.substring(from, giving), in);
            ValueInterval result = Intervals.sub(minuend, subtrahend);
            for (String dst : topLevelNames(tail(body, giving, "GIVING"))) {
                out.put(dst, result);
            }
            return;
        }
        for (String dst : topLevelNames(tail(body, from, "FROM"))) {
            out.put(dst, Intervals.sub(resolve(dst, in), subtrahend));
        }
    }

    private void multiply(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-MULTIPLY ");
        int by = kw(body, "BY");
        if (by < 0) {
            return;
        }
        ValueInterval left = firstOperand(body.substring(0, by), in);
        int giving = kw(body, "GIVING");
        if (giving >= 0) {
            ValueInterval right = firstOperand(body.substring(by, giving), in);
            ValueInterval result = Intervals.mul(left, right);
            for (String dst : topLevelNames(tail(body, giving, "GIVING"))) {
                out.put(dst, result);
            }
            return;
        }
        for (String dst : topLevelNames(tail(body, by, "BY"))) {
            out.put(dst, Intervals.mul(resolve(dst, in), left));
        }
    }

    private void compute(String u, Map<String, ValueInterval> in, Map<String, ValueInterval> out) {
        String body = cutBefore(u, " ON SIZE ERROR ", " END-COMPUTE ");
        int eq = body.indexOf('=');
        if (eq < 0) {
            return;
        }
        ValueInterval value = IntervalExpr.eval(body.substring(eq + 1), tok -> resolve(tok, in));
        for (String dst : topLevelNames(body.substring(0, eq))) {
            out.put(dst, value);
        }
    }

    /** INITIALIZE は数字項目を 0 で埋める。PIC 境界を持たない項目は数字か判別できず非有界とする。 */
    private void initialize(String u, Map<String, ValueInterval> out) {
        int replacing = kw(u, "REPLACING");
        String items = replacing >= 0 ? u.substring(0, replacing) : u;
        for (String name : topLevelNames(items)) {
            out.put(name, picBounds.containsKey(name) ? ValueInterval.point(0) : Intervals.UNBOUNDED);
        }
    }

    private void defsToUnbounded(Statement statement, Map<String, ValueInterval> out) {
        for (String def : DefUseAnalyzer.extract(statement).defs()) {
            out.put(def, Intervals.UNBOUNDED);
        }
    }

    // ---- オペランド解決 ----

    private ValueInterval sumOperands(String region, Map<String, ValueInterval> state) {
        ValueInterval sum = ValueInterval.point(0);
        boolean any = false;
        for (String token : operandTokens(region)) {
            sum = Intervals.add(sum, resolve(token, state));
            any = true;
        }
        return any ? sum : Intervals.UNBOUNDED;
    }

    private ValueInterval firstOperand(String region, Map<String, ValueInterval> state) {
        String token = firstOperandToken(region);
        return token == null ? Intervals.UNBOUNDED : resolve(token, state);
    }

    private ValueInterval resolve(String token, Map<String, ValueInterval> state) {
        if (token == null) {
            return Intervals.UNBOUNDED;
        }
        Long literal = parseLiteral(token);
        if (literal != null) {
            return ValueInterval.point(literal);
        }
        String name = token.toUpperCase(Locale.ROOT);
        int paren = name.indexOf('(');
        if (paren >= 0) {
            name = name.substring(0, paren);
        }
        if (name.equals("ZERO") || name.equals("ZEROS") || name.equals("ZEROES")) {
            return ValueInterval.point(0);
        }
        ValueInterval s = state.get(name);
        if (s != null) {
            return s;
        }
        ValueInterval pb = picBounds.get(name);
        return pb != null ? pb : Intervals.UNBOUNDED;
    }

    private static Long parseLiteral(String token) {
        String t = token.trim().toUpperCase(Locale.ROOT);
        if (t.equals("ZERO") || t.equals("ZEROS") || t.equals("ZEROES")) {
            return 0L;
        }
        if (!t.matches("[+-]?\\d+")) {
            return null;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- ループ句の事前解析と後退辺(widening 点)検出 ----

    private Map<CfgNode, LoopClause> loopClauses(ControlFlowGraph cfg) {
        Map<CfgNode, LoopClause> map = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            if (node.kind() != CfgNodeKind.STATEMENT) {
                continue;
            }
            Statement statement = node.statement().orElseThrow();
            if (statement instanceof CompoundStatement compound
                    && compound.kind() == ControlKind.LOOP) {
                map.put(node, parseLoop(compound.conditionText(), true));
            } else if (statement instanceof SimpleStatement simple
                    && "PERFORM".equals(simple.verb().toUpperCase(Locale.ROOT))) {
                LoopClause clause = parseLoop(simple.text(), false);
                if (clause.varyingVar() != null || clause.until() != null) {
                    map.put(node, clause);
                }
            }
        }
        return map;
    }

    private LoopClause parseLoop(String text, boolean loopCompound) {
        String u = clean(text);
        int varying = kw(u, "VARYING");
        int until = kw(u, "UNTIL");
        String varyingVar = null;
        String fromToken = null;
        String byToken = null;
        if (varying >= 0) {
            String region = until >= 0 && until > varying
                    ? u.substring(varying, until) : u.substring(varying);
            varyingVar = firstNameToken(tail(region, kw(region, "VARYING"), "VARYING"));
            int from = kw(region, "FROM");
            if (from >= 0) {
                fromToken = firstOperandToken(tail(region, from, "FROM"));
            }
            int by = kw(region, "BY");
            if (by >= 0) {
                byToken = firstOperandToken(tail(region, by, "BY"));
            }
        }
        Comparison untilCond = null;
        if (until >= 0) {
            untilCond = parseComparison(tail(u, until, "UNTIL"));
        } else if (loopCompound && varying < 0) {
            untilCond = parseComparison(u);
        }
        return new LoopClause(varyingVar, fromToken, byToken, untilCond);
    }

    private Comparison parseComparison(String region) {
        int[] found = findRelation(region);
        if (found == null) {
            return null;
        }
        Rel rel = relOf(region.substring(found[0], found[1]));
        String var = firstNameToken(region.substring(0, found[0]));
        String bound = firstOperandToken(region.substring(found[1]));
        return new Comparison(var, rel, bound);
    }

    /** 継続条件の関係演算子の位置 [開始,終了) を返す。無ければ null。 */
    private static int[] findRelation(String region) {
        String[] worded = {" NOT = ", " NOT EQUAL ", " GREATER THAN OR EQUAL ",
                " LESS THAN OR EQUAL ", " GREATER THAN ", " LESS THAN ", " EQUAL "};
        for (String w : worded) {
            int idx = region.indexOf(w);
            if (idx >= 0) {
                return new int[] {idx, idx + w.length()};
            }
        }
        String[] symbols = {">=", "<=", ">", "<", "="};
        for (String s : symbols) {
            int idx = region.indexOf(s);
            if (idx >= 0) {
                return new int[] {idx, idx + s.length()};
            }
        }
        return null;
    }

    private static Rel relOf(String op) {
        String o = op.trim();
        return switch (o) {
            case ">", "GREATER THAN" -> Rel.GT;
            case ">=", "GREATER THAN OR EQUAL" -> Rel.GE;
            case "<", "LESS THAN" -> Rel.LT;
            case "<=", "LESS THAN OR EQUAL" -> Rel.LE;
            case "=", "EQUAL" -> Rel.EQ;
            case "NOT =", "NOT EQUAL" -> Rel.NE;
            default -> Rel.OTHER;
        };
    }

    /** 後退辺の終端ノード(ループ header)を widening 点とする。全サイクルが1点以上を含む。 */
    private static Set<CfgNode> wideningNodes(ControlFlowGraph cfg) {
        Map<CfgNode, Set<CfgNode>> reach = new IdentityHashMap<>();
        for (CfgNode node : cfg.nodes()) {
            reach.put(node, forwardReach(cfg, node));
        }
        Set<CfgNode> headers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (CfgNode u : cfg.nodes()) {
            for (CfgNode v : cfg.successors(u)) {
                if (reach.get(v).contains(u)) {
                    headers.add(v);
                }
            }
        }
        return headers;
    }

    private static Set<CfgNode> forwardReach(ControlFlowGraph cfg, CfgNode start) {
        Set<CfgNode> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<CfgNode> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            CfgNode node = queue.removeFirst();
            for (CfgNode succ : cfg.successors(node)) {
                if (visited.add(succ)) {
                    queue.addLast(succ);
                }
            }
        }
        return visited;
    }

    // ---- テキスト操作(DefUseAnalyzer の text 方式に準拠) ----

    private static final Set<String> RESERVED = Set.of(
            // 動詞
            "MOVE", "SET", "COMPUTE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "INITIALIZE",
            "DISPLAY", "ACCEPT", "CALL", "READ", "WRITE", "REWRITE", "STRING", "UNSTRING",
            "PERFORM", "GOBACK", "STOP", "RUN", "EXIT", "PROGRAM", "CONTINUE",
            // 句・接続詞
            "ROUNDED", "CORRESPONDING", "CORR", "DEPENDING", "ON", "OFF", "SIZE", "ERROR",
            "TO", "FROM", "BY", "INTO", "GIVING", "REMAINDER", "UP", "DOWN", "THRU", "THROUGH",
            "UNTIL", "VARYING", "AFTER", "BEFORE", "TEST", "TIMES", "TALLYING", "USING",
            "RETURNING", "REPLACING", "COUNT", "OVERFLOW", "POINTER", "DELIMITED", "DELIMITER",
            "AND", "OR", "NOT", "IS", "THAN", "WITH", "OF", "IN",
            // 定数図形
            "ZERO", "ZEROS", "ZEROES", "SPACE", "SPACES", "HIGH-VALUE", "HIGH-VALUES",
            "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES", "GREATER", "LESS", "EQUAL");

    /** 括弧の外側にある最初のデータ名/数値リテラルのトークン。無ければ null。 */
    private static String firstOperandToken(String region) {
        int depth = 0;
        int i = 0;
        int n = region.length();
        while (i < n) {
            char c = region.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
            } else if (depth == 0 && Character.isDigit(c)) {
                int j = i;
                while (j < n && Character.isDigit(region.charAt(j))) {
                    j++;
                }
                return region.substring(i, j);
            } else if (depth == 0 && Character.isLetter(c)) {
                int j = i;
                while (j < n && isNameChar(region.charAt(j))) {
                    j++;
                }
                String tok = region.substring(i, j);
                if (!RESERVED.contains(tok.toUpperCase(Locale.ROOT))) {
                    return tok;
                }
                i = j;
            } else {
                i++;
            }
        }
        return null;
    }

    /** 括弧の外側にある最初のデータ名(数値・予約語は除外)。無ければ null。 */
    private static String firstNameToken(String region) {
        int depth = 0;
        int i = 0;
        int n = region.length();
        while (i < n) {
            char c = region.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
            } else if (depth == 0 && Character.isLetter(c)) {
                int j = i;
                while (j < n && isNameChar(region.charAt(j))) {
                    j++;
                }
                String tok = region.substring(i, j);
                if (!RESERVED.contains(tok.toUpperCase(Locale.ROOT))) {
                    return tok.toUpperCase(Locale.ROOT);
                }
                i = j;
            } else {
                i++;
            }
        }
        return null;
    }

    /** 括弧の外側にあるデータ名(添字・部分参照は除外、予約語・数値は除外)を出現順に返す。 */
    private static Set<String> topLevelNames(String region) {
        Set<String> out = new LinkedHashSet<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < region.length(); i++) {
            char c = region.charAt(i);
            if (c == '(') {
                flushName(cur, out);
                depth++;
            } else if (c == ')') {
                flushName(cur, out);
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && isNameChar(c)) {
                cur.append(c);
            } else {
                flushName(cur, out);
            }
        }
        flushName(cur, out);
        return out;
    }

    /** 括弧の外側にあるオペランド(データ名・数値リテラル)を出現順に返す。 */
    private static List<String> operandTokens(String region) {
        List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < region.length(); i++) {
            char c = region.charAt(i);
            if (c == '(') {
                flushOperand(cur, out);
                depth++;
            } else if (c == ')') {
                flushOperand(cur, out);
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && isNameChar(c)) {
                cur.append(c);
            } else {
                flushOperand(cur, out);
            }
        }
        flushOperand(cur, out);
        return out;
    }

    private static void flushName(StringBuilder cur, Set<String> out) {
        String tok = cur.toString();
        cur.setLength(0);
        if (isDataName(tok)) {
            out.add(tok.toUpperCase(Locale.ROOT));
        }
    }

    private static void flushOperand(StringBuilder cur, List<String> out) {
        String tok = cur.toString();
        cur.setLength(0);
        if (tok.isEmpty()) {
            return;
        }
        if (parseLiteral(tok) != null) {
            out.add(tok);
        } else if (isDataName(tok)) {
            out.add(tok.toUpperCase(Locale.ROOT));
        }
    }

    private static boolean isDataName(String tok) {
        if (tok.isEmpty() || RESERVED.contains(tok.toUpperCase(Locale.ROOT))) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < tok.length(); ) {
            int cp = tok.codePointAt(i);
            if (Character.isLetter(cp)) {
                hasLetter = true;
                break;
            }
            i += Character.charCount(cp);
        }
        return hasLetter;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-';
    }

    /** 文字列リテラルを空白化し、大文字化し、空白を単一空白へ畳み、前後を空白で囲む。 */
    private static String clean(String text) {
        String stripped = stripLiterals(text).toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        return " " + stripped + " ";
    }

    private static String stripLiterals(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                sb.append(' ');
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int kw(String u, String keyword) {
        return u.indexOf(" " + keyword + " ");
    }

    private static String tail(String u, int keywordStart, String keyword) {
        if (keywordStart < 0) {
            return u;
        }
        return u.substring(keywordStart + keyword.length() + 2);
    }

    private static String cutBefore(String s, String... markers) {
        int min = s.length();
        for (String marker : markers) {
            int idx = s.indexOf(marker);
            if (idx >= 0 && idx < min) {
                min = idx;
            }
        }
        return s.substring(0, min);
    }
}
