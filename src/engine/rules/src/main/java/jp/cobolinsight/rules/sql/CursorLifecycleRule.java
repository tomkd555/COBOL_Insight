package jp.cobolinsight.rules.sql;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.sql.CursorSignals;
import jp.cobolinsight.core.sql.SqlSetPair;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.cfg.CfgSupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R055 A cursor used out of order. Five defects, each read off the program's control flow graph and
 * each reported once per cursor, at the statement that is at fault:
 *
 * <ul>
 *   <li>A FETCH or CLOSE reached without an OPEN: either no OPEN of the cursor anywhere in the
 *       program, or an OPEN its own paragraph can walk around because a branch holds it. SQLCODE
 *       -501 on the first run through that path.</li>
 *   <li>A second OPEN statement the flow reaches while the cursor is open already, with no CLOSE
 *       and no end of the unit of work in between: SQLCODE -502.</li>
 *   <li>A DECLARE CURSOR that nothing opens: the query never runs, and the FETCH loop meant to read
 *       it was either dropped or never written.</li>
 *   <li>A FETCH the flow reaches from a ROLLBACK with no OPEN in between. A ROLLBACK closes every
 *       cursor, WITH HOLD included, so the FETCH fails with SQLCODE -501.</li>
 *   <li>A positioned UPDATE or DELETE on a cursor declared FOR READ ONLY or FOR FETCH ONLY, or an
 *       UPDATE writing a column outside the cursor's FOR UPDATE OF list: SQLCODE -510 or -503. A
 *       declaration that states neither FOR UPDATE nor a read-only clause is left to S004: it is
 *       read-only or not by the precompile option, and the coprocessor of a current z/OS shop
 *       leaves it updatable.</li>
 * </ul>
 *
 * <p>A COMMIT closing a cursor declared without WITH HOLD is R039's finding and not repeated here;
 * it counts only as an end of the unit of work, which stops the search for a second OPEN. On a CICS
 * program the boundary is {@code EXEC CICS SYNCPOINT} instead, because such a program carries no
 * {@code EXEC SQL COMMIT} to find, and its SYNCPOINT nodes join the boundary set the same way.
 *
 * <p>The reachability is the CFG's own — statement to statement across paragraph fall-through and
 * PERFORM edges — and knows nothing of which PERFORM range a paragraph was entered from. Two of
 * the checks are narrowed for that reason, each measured against the corpus folders before being
 * narrowed: the walk for a skipped OPEN stays inside one paragraph, and a second OPEN has to be a
 * second statement. Both limits cost cross-paragraph cases and are what keeps the rule off the
 * paths this CFG offers but the program does not have. For the same reason a skipped OPEN whose
 * branch turns its own guard off is left alone: the paragraph opens the cursor on its first entry
 * and is performed again, so the branch that walks around the OPEN runs only while the cursor is
 * open.
 */
public final class CursorLifecycleRule implements Rule {

    /** An {@code EXEC CICS SYNCPOINT}, which ends the unit of work of a CICS program. */
    private static final Pattern SYNCPOINT =
            Pattern.compile("(?is)\\bEXEC\\s+CICS\\b.*?\\bSYNCPOINT\\b");

    /** The data item a statement writes: the first name after TO, INTO or SET. */
    private static final Pattern WRITE_TARGET = Pattern.compile(
            "(?is)\\b(?:TO|INTO|SET)\\s+([\\p{L}\\p{N}$#_-]*\\p{L}[\\p{L}\\p{N}$#_-]*)");

    /** What is wrong with a cursor's use. One finding per cursor per constant. */
    private enum Defect {
        NO_OPEN, DOUBLE_OPEN, NEVER_OPENED, AFTER_ROLLBACK, NOT_FOR_UPDATE
    }

    private static final RuleMeta META =
            RuleMeta.named("R055", "順序の誤ったカーソルの操作", "SQL")
            .summary("OPEN していないカーソルの FETCH・CLOSE、開いたままの OPEN、"
                    + "OPEN されない宣言、ROLLBACK 後の FETCH、"
                    + "読み取り専用で宣言したカーソルへの位置づけ更新・削除を検出します。")
            .rationale("カーソルの状態が合わず、SQLCODE -501・-502・-510 で失敗します。"
                    + "OPEN されない宣言では、読むはずの行を 1 件も読みません。")
            .detection("制御フローをたどり、カーソルごとに次の 5 つを検出します。"
                    + "OPEN を通らずに到達する FETCH・CLOSE、"
                    + "CLOSE も同期点も通らずに到達する 2 つ目の OPEN 文、"
                    + "どこからも OPEN されない DECLARE CURSOR、"
                    + "ROLLBACK から OPEN を通らずに到達する FETCH、"
                    + "FOR READ ONLY・FOR FETCH ONLY で宣言したカーソル、"
                    + "または FOR UPDATE OF に挙げていない列への "
                    + "WHERE CURRENT OF を伴う更新です。"
                    + "FOR UPDATE も読み取り専用の指定もない宣言は、"
                    + "プリコンパイルの指定で結果が変わるため対象外です。"
                    + "1 つ目の検出は、OPEN がプログラムのどこにもない場合と、"
                    + "同じ段落の中の分岐で OPEN を迂回できる場合に限ります。"
                    + "別の段落にある OPEN は実行済みとして扱います。"
                    + "分岐の条件がその分岐自身の書き換えるデータ項目であれば、"
                    + "初回だけ OPEN する書き方として対象外です。"
                    + "ALLOCATE CURSOR は OPEN として扱います。"
                    + "WITH HOLD のないカーソルを開いたままの COMMIT は R039 が報告するため、"
                    + "ここでは同期点として扱うだけです。"
                    + "EXEC CICS を含むプログラムでは、EXEC CICS SYNCPOINT を同期点とします。"
                    + "ROLLBACK 後の FETCH は、そのカーソルの OPEN から到達する "
                    + "ROLLBACK だけを対象とします。")
            .remedy("OPEN・FETCH・CLOSE の順序を経路ごとにそろえ、"
                    + "更新するカーソルの宣言に FOR UPDATE OF と対象の列を書いてください。")
            .example("""
                    PERFORM 3100-FETCH UNTIL SQLCODE = 100.
                    3100-FETCH.
                        EXEC SQL FETCH CSR-ZAIKO INTO :HOST-在庫数量 END-EXEC.
                    """, """
                    EXEC SQL OPEN CSR-ZAIKO END-EXEC.
                    PERFORM 3100-FETCH UNTIL SQLCODE = 100.
                    EXEC SQL CLOSE CSR-ZAIKO END-EXEC.
                    """)
            .severity(Severity.MEDIUM)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .needs(Needs.CFG, Needs.SQL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        ControlFlowGraphs cfgs = context.artifact(ControlFlowGraphs.class).orElse(null);
        if (cfgs == null) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            List<SqlStatementModel> statements =
                    SqlAdviceSupport.statementsOf(model, context.sqlStatements());
            if (!statements.isEmpty()) {
                List<SqlStatementModel> copied =
                        SqlAdviceSupport.copiedStatements(model, context.sqlStatements());
                cfgs.of(model).ifPresent(cfg -> evaluate(model, cfg, statements, copied, findings));
            }
        }
        return findings;
    }

    /** One program: the cursor statements sorted by cursor, then each defect kind in turn. */
    private static void evaluate(CobolSemanticModel model, ControlFlowGraph cfg,
            List<SqlStatementModel> statements, List<SqlStatementModel> copied,
            List<Finding> findings) {
        Map<String, SqlStatementModel> declares = new LinkedHashMap<>();
        // A cursor declared in a copybook has no node of this CFG, so it is no statement of the
        // program; what its declaration says still decides whether a COMMIT closes the cursor.
        Map<String, SqlStatementModel> copiedDeclares = new LinkedHashMap<>();
        for (SqlStatementModel statement : copied) {
            if (statement.kind() == SqlStatementKind.DECLARE_CURSOR) {
                put(copiedDeclares, statement.cursorName().map(CursorLifecycleRule::key)
                        .orElse(null), statement);
            }
        }
        Map<String, List<SqlStatementModel>> opens = new LinkedHashMap<>();
        Map<String, List<SqlStatementModel>> fetches = new LinkedHashMap<>();
        Map<String, List<SqlStatementModel>> closes = new LinkedHashMap<>();
        List<SqlStatementModel> rollbacks = new ArrayList<>();
        List<SqlStatementModel> commits = new ArrayList<>();
        List<SqlStatementModel> positioned = new ArrayList<>();
        for (SqlStatementModel statement : statements) {
            String cursor = statement.cursorName().map(CursorLifecycleRule::key).orElse(null);
            switch (statement.kind()) {
                case DECLARE_CURSOR -> put(declares, cursor, statement);
                // ALLOCATE CURSOR FOR RESULT SET both declares the cursor and leaves it open, so
                // it stands for the OPEN a program calling a stored procedure never writes.
                case OPEN, ALLOCATE_CURSOR -> add(opens, cursor, statement);
                case FETCH -> add(fetches, cursor, statement);
                case CLOSE -> add(closes, cursor, statement);
                case ROLLBACK -> rollbacks.add(statement);
                case COMMIT -> commits.add(statement);
                default -> {
                    if (statement.positionedCursor().isPresent()) {
                        positioned.add(statement);
                    }
                }
            }
        }
        Map<CfgNode, SqlStatementModel> byNode = new IdentityHashMap<>();
        Map<SqlStatementModel, CfgNode> nodes = new LinkedHashMap<>();
        for (SqlStatementModel statement : statements) {
            SqlAdviceSupport.cfgNodeOf(cfg, statement).ifPresent(node -> {
                nodes.put(statement, node);
                byNode.put(node, statement);
            });
        }
        // On a CICS program the unit of work ends with SYNCPOINT rather than with an SQL COMMIT,
        // and a CICS program carries no EXEC SQL COMMIT at all, so its SYNCPOINT nodes stand in.
        Set<CfgNode> syncpoints = Db2Schema.hasCics(model)
                ? syncpointNodes(cfg) : Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> reported = new LinkedHashSet<>();
        Set<String> cursors = new LinkedHashSet<>(declares.keySet());
        cursors.addAll(opens.keySet());
        cursors.addAll(fetches.keySet());
        cursors.addAll(closes.keySet());
        for (String cursor : cursors) {
            List<SqlStatementModel> cursorOpens = opens.getOrDefault(cursor, List.of());
            List<SqlStatementModel> uses = new ArrayList<>(fetches.getOrDefault(cursor, List.of()));
            uses.addAll(closes.getOrDefault(cursor, List.of()));
            if (cursorOpens.isEmpty()) {
                if (declares.containsKey(cursor)) {
                    report(findings, reported, cursor, Defect.NEVER_OPENED, declares.get(cursor),
                            cursor + " は DECLARE されていますが、どこからも OPEN されていません。"
                                    + "この問い合わせは実行されず、1 行も読みません。");
                } else if (!uses.isEmpty()) {
                    report(findings, reported, cursor, Defect.NO_OPEN, uses.get(0),
                            cursor + " を OPEN する文がプログラムのどこにもありません。"
                                    + "SQLCODE -501 で失敗します。");
                }
            } else {
                noOpen(cfg, cursor, cursorOpens, uses, nodes, findings, reported);
            }
            doubleOpen(cfg, cursor, cursorOpens, closes.getOrDefault(cursor, List.of()), rollbacks,
                    commits, syncpoints,
                    holds(declares.getOrDefault(cursor, copiedDeclares.get(cursor))), nodes, byNode,
                    findings, reported);
            afterRollback(cfg, cursor, cursorOpens, fetches.getOrDefault(cursor, List.of()),
                    rollbacks, nodes, byNode, findings, reported);
        }
        notForUpdate(positioned, declares, findings, reported);
    }

    /**
     * A FETCH or CLOSE its own paragraph reaches without passing an OPEN standing in that
     * paragraph, which is the conditional OPEN the flow can walk around.
     */
    private static void noOpen(ControlFlowGraph cfg, String cursor,
            List<SqlStatementModel> opens, List<SqlStatementModel> uses,
            Map<SqlStatementModel, CfgNode> nodes, List<Finding> findings, Set<String> reported) {
        Set<CfgNode> openNodes = nodesOf(opens, nodes);
        for (SqlStatementModel use : uses) {
            CfgNode node = nodes.get(use);
            if (node == null || !skipsTheOpen(cfg, node, openNodes)) {
                continue;
            }
            String verb = use.kind() == SqlStatementKind.FETCH ? "FETCH" : "CLOSE";
            if (report(findings, reported, cursor, Defect.NO_OPEN, use,
                    cursor + " の " + verb + " に到達する経路に、そのカーソルの OPEN が"
                            + "ありません。SQLCODE -501 で失敗します。")) {
                return;
            }
        }
    }

    /**
     * Whether the paragraph holding {@code use} reaches it from its own first statement without
     * passing an OPEN of the cursor. The walk stays inside the paragraph on purpose: a PERFORM
     * node of this CFG keeps the edge to the statement after it as well as the edge into the
     * paragraph it calls, so a walk from the graph's entry can step over every PERFORM body and
     * would call almost any statement reachable with nothing executed before it. An OPEN in
     * another paragraph is therefore taken to have run, and so is an OPEN the paragraph runs on its
     * first entry alone.
     */
    private static boolean skipsTheOpen(ControlFlowGraph cfg, CfgNode use, Set<CfgNode> openNodes) {
        String procedure = use.procedureName();
        if (openNodes.stream().noneMatch(open -> open.procedureName().equals(procedure))
                || opensOnFirstEntry(cfg, openNodes, procedure)) {
            return false;
        }
        CfgNode start = cfg.nodes().stream()
                .filter(node -> node.procedureName().equals(procedure)).findFirst().orElse(null);
        if (start == null || openNodes.contains(start)) {
            return false;
        }
        if (start == use) {
            return true;
        }
        return CfgSupport.forwardHasMatch(cfg, start,
                node -> openNodes.contains(node) || !node.procedureName().equals(procedure),
                node -> node == use);
    }

    /**
     * Whether an OPEN of the paragraph stands under a first-time flag: a branch whose condition
     * tests a data item that the same branch writes. The paragraph then opens the cursor on its
     * first entry and walks around the OPEN on every later one, which is the "open once, perform
     * the read paragraph until the rows run out" idiom rather than a path that fetches a cursor
     * nothing opened.
     */
    private static boolean opensOnFirstEntry(ControlFlowGraph cfg, Set<CfgNode> openNodes,
            String procedure) {
        Set<Statement> opens = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CfgNode open : openNodes) {
            open.statement().ifPresent(opens::add);
        }
        for (CfgNode node : cfg.nodes()) {
            if (!node.procedureName().equals(procedure)
                    || !(node.statement().orElse(null) instanceof CompoundStatement branch)) {
                continue;
            }
            for (StatementBlock block : branch.blocks()) {
                if (holdsOneOf(block.statements(), opens)
                        && writesAnItemOf(block.statements(), branch.conditionText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether one of the statements, or one nested in them, is among {@code wanted}. */
    private static boolean holdsOneOf(List<Statement> statements, Set<Statement> wanted) {
        for (Statement statement : statements) {
            if (wanted.contains(statement)) {
                return true;
            }
            if (statement instanceof CompoundStatement compound && compound.blocks().stream()
                    .anyMatch(block -> holdsOneOf(block.statements(), wanted))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one of the statements gives a value to a data item the condition tests. What a
     * statement writes is taken to be the first name after TO, INTO or SET, which is the target of
     * the {@code MOVE 'N' TO WS-初回} the idiom turns the flag off with; the model carries the
     * statement as text, so the name is read off that rather than from a data-flow query this rule
     * does not ask for.
     */
    private static boolean writesAnItemOf(List<Statement> statements, String condition) {
        for (Statement statement : statements) {
            if (statement instanceof SimpleStatement simple) {
                Matcher target = WRITE_TARGET.matcher(simple.text());
                if (target.find()
                        && CfgSupport.wordPattern(target.group(1)).matcher(condition).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** An OPEN the flow reaches again before a CLOSE or an end of the unit of work. */
    private static void doubleOpen(ControlFlowGraph cfg, String cursor,
            List<SqlStatementModel> opens, List<SqlStatementModel> closes,
            List<SqlStatementModel> rollbacks, List<SqlStatementModel> commits,
            Set<CfgNode> syncpoints, boolean holds, Map<SqlStatementModel, CfgNode> nodes,
            Map<CfgNode, SqlStatementModel> byNode, List<Finding> findings,
            Set<String> reported) {
        Set<CfgNode> openNodes = nodesOf(opens, nodes);
        Set<CfgNode> boundary = nodesOf(closes, nodes);
        boundary.addAll(nodesOf(rollbacks, nodes));
        if (!holds) {
            boundary.addAll(nodesOf(commits, nodes));
            boundary.addAll(syncpoints);
        }
        for (SqlStatementModel open : opens) {
            CfgNode from = nodes.get(open);
            if (from == null) {
                continue;
            }
            // Only a second OPEN statement counts. The same OPEN reached again is what this CFG's
            // PERFORM return edges produce for any paragraph the flow re-enters, not a second
            // execution the source states.
            CfgNode again = CfgSupport.firstMatch(cfg, from, boundary::contains,
                    node -> node != from && openNodes.contains(node)).orElse(null);
            if (again != null && report(findings, reported, cursor, Defect.DOUBLE_OPEN,
                    byNode.get(again),
                    cursor + " は " + open.range().start().line()
                            + " 行で OPEN したまま、CLOSE も同期点も通らずに再び OPEN されます。"
                            + "SQLCODE -502 で失敗します。")) {
                return;
            }
        }
    }

    /**
     * A FETCH the flow reaches from a ROLLBACK with no OPEN of the same cursor in between, where the
     * ROLLBACK itself is one an OPEN of that cursor reaches. Without the second half every ROLLBACK
     * of the program would be walked for every cursor, and an error paragraph that falls through
     * into a fetch paragraph would yield a -501 no execution can produce.
     */
    private static void afterRollback(ControlFlowGraph cfg, String cursor,
            List<SqlStatementModel> opens, List<SqlStatementModel> fetches,
            List<SqlStatementModel> rollbacks, Map<SqlStatementModel, CfgNode> nodes,
            Map<CfgNode, SqlStatementModel> byNode, List<Finding> findings,
            Set<String> reported) {
        Set<CfgNode> openNodes = nodesOf(opens, nodes);
        Set<CfgNode> fetchNodes = nodesOf(fetches, nodes);
        for (SqlStatementModel rollback : rollbacks) {
            CfgNode from = nodes.get(rollback);
            if (from == null || !openedBefore(cfg, openNodes, from)) {
                continue;
            }
            CfgNode fetch = CfgSupport
                    .firstMatch(cfg, from, openNodes::contains, fetchNodes::contains).orElse(null);
            if (fetch != null && report(findings, reported, cursor, Defect.AFTER_ROLLBACK,
                    byNode.get(fetch),
                    cursor + " を " + rollback.range().start().line()
                            + " 行の ROLLBACK の後で FETCH しています。ROLLBACK は WITH HOLD の"
                            + "カーソルも閉じるため、SQLCODE -501 で失敗します。")) {
                return;
            }
        }
    }

    /**
     * The clause that makes the cursor read-only, or null where the declaration states neither of
     * them. A declaration with no FOR UPDATE and no read-only clause is ambiguous rather than
     * wrong: the precompiler's NOFOR option, which the SQL statement coprocessor of a current z/OS
     * shop applies, leaves such a cursor open to a positioned UPDATE or DELETE. S004 reports the
     * ambiguity itself, with the milder message it deserves.
     */
    private static String readOnlyClause(SqlStatementModel declare) {
        CursorSignals signals = declare.structureSignals().cursor().orElse(null);
        if (signals == null || !(signals.forReadOnly() || signals.forFetchOnly())) {
            return null;
        }
        return signals.forReadOnly() ? "FOR READ ONLY" : "FOR FETCH ONLY";
    }

    /** What the positioned statement does to the row the cursor stands on. */
    private static String act(SqlStatementModel statement) {
        return statement.kind() == SqlStatementKind.DELETE ? "削除" : "更新";
    }

    /** A positioned UPDATE or DELETE the cursor's declaration does not allow. */
    private static void notForUpdate(List<SqlStatementModel> positioned,
            Map<String, SqlStatementModel> declares, List<Finding> findings,
            Set<String> reported) {
        for (SqlStatementModel statement : positioned) {
            String cursor = key(statement.positionedCursor().orElseThrow());
            SqlStatementModel declare = declares.get(cursor);
            if (declare == null || !declare.isFullyAnalysed()) {
                continue;
            }
            String readOnly = readOnlyClause(declare);
            if (readOnly != null) {
                report(findings, reported, cursor, Defect.NOT_FOR_UPDATE, statement,
                        cursor + " は " + readOnly + " で宣言されています。"
                                + "WHERE CURRENT OF による" + act(statement)
                                + "は SQLCODE -510 で失敗します。");
                continue;
            }
            List<String> allowed = declare.forUpdateColumns().stream()
                    .map(CursorLifecycleRule::key).toList();
            if (allowed.isEmpty()) {
                continue;
            }
            for (SqlSetPair pair : statement.setPairs()) {
                // The SET column may carry the table or the correlation name of the UPDATE, which
                // the FOR UPDATE OF list never does, so the qualifier comes off before the match.
                if (!allowed.contains(Db2Schema.unqualified(pair.column()))) {
                    report(findings, reported, cursor, Defect.NOT_FOR_UPDATE, statement,
                            pair.column() + " は " + cursor
                                    + " の FOR UPDATE OF に挙げられていません。"
                                    + "この更新は SQLCODE -503 で失敗します。");
                    break;
                }
            }
        }
    }

    /** Whether an OPEN of the cursor reaches the node, so the cursor can be open when it runs. */
    private static boolean openedBefore(ControlFlowGraph cfg, Set<CfgNode> openNodes,
            CfgNode node) {
        for (CfgNode open : openNodes) {
            if (open == node || CfgSupport.forwardHasMatch(cfg, open, candidate -> false,
                    candidate -> candidate == node)) {
                return true;
            }
        }
        return false;
    }

    /** The CFG nodes of the program's {@code EXEC CICS SYNCPOINT} blocks. */
    private static Set<CfgNode> syncpointNodes(ControlFlowGraph cfg) {
        Set<CfgNode> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CfgNode node : cfg.nodes()) {
            if (node.statement().orElse(null) instanceof SimpleStatement simple
                    && "EXEC CICS".equals(simple.verb())
                    && SYNCPOINT.matcher(simple.text()).find()) {
                found.add(node);
            }
        }
        return found;
    }

    private static boolean holds(SqlStatementModel declare) {
        return declare != null && declare.isFullyAnalysed() && declare.withHold();
    }

    private static Set<CfgNode> nodesOf(List<SqlStatementModel> statements,
            Map<SqlStatementModel, CfgNode> nodes) {
        Set<CfgNode> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SqlStatementModel statement : statements) {
            CfgNode node = nodes.get(statement);
            if (node != null) {
                found.add(node);
            }
        }
        return found;
    }

    /** Records the finding unless this cursor already has one of this kind. */
    private static boolean report(List<Finding> findings, Set<String> reported, String cursor,
            Defect defect, SqlStatementModel at, String message) {
        if (at == null || !reported.add(cursor + " " + defect)) {
            return false;
        }
        findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(), message,
                SqlAdviceSupport.location(at)));
        return true;
    }

    private static void put(Map<String, SqlStatementModel> map, String cursor,
            SqlStatementModel statement) {
        if (cursor != null) {
            map.putIfAbsent(cursor, statement);
        }
    }

    private static void add(Map<String, List<SqlStatementModel>> map, String cursor,
            SqlStatementModel statement) {
        if (cursor != null) {
            map.computeIfAbsent(cursor, key -> new ArrayList<>()).add(statement);
        }
    }

    private static String key(String name) {
        return name.toUpperCase(Locale.ROOT);
    }
}
