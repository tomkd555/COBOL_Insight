package jp.cobolinsight.linker;

import jp.cobolinsight.engineapi.bms.BmsMap;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.callgraph.CallGraph;
import jp.cobolinsight.engineapi.callgraph.CallGraphEdge;
import jp.cobolinsight.engineapi.callgraph.CallGraphNode;
import jp.cobolinsight.engineapi.callgraph.EdgeKind;
import jp.cobolinsight.engineapi.callgraph.NodeKind;
import jp.cobolinsight.engineapi.callgraph.Resolution;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.jcl.JclDdStatement;
import jp.cobolinsight.engineapi.jcl.JclExecKind;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.semantic.CallKind;
import jp.cobolinsight.engineapi.semantic.CallRelation;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.CompoundStatement;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.Procedure;
import jp.cobolinsight.engineapi.semantic.SimpleStatement;
import jp.cobolinsight.engineapi.semantic.Statement;
import jp.cobolinsight.engineapi.semantic.StatementBlock;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 呼出関係グラフの統合。JCL(EXEC PGM=)とCOBOL(PROGRAM-ID)の対応、静的CALL解決、
 * 動的CALLの定数伝播(MOVE 定数→CALL 変数)による解決、未解決ノード・外部ユーティリティ
 * ノードの型付け、EXEC CICS のトランザクション遷移辺・マップ参照辺、トランザクション定義表に
 * よるトランザクションID→プログラム解決を行い、単一の {@link CallGraph} を構築する。
 * ノードIDは種別接頭辞付きの文字列で決定論的に定める。
 */
public final class CallGraphLinker {

    /** 動的CALLを定数伝播で解決したとき、解決根拠(定数由来)を記録するルールID。 */
    public static final String DYNAMIC_CALL_RESOLVED_RULE_ID = "callgraph-dynamic-call";
    /** 動的CALLを解決できなかったことを記録するルールID。 */
    public static final String DYNAMIC_CALL_UNRESOLVED_RULE_ID = "callgraph-dynamic-call-unresolved";
    /** トランザクションIDをプログラムへ解決できなかったことを記録するルールID。 */
    public static final String TRANSACTION_UNRESOLVED_RULE_ID = "callgraph-transaction-unresolved";

    private static final Set<String> EXTERNAL_UTILITIES = Set.of("DFSORT", "IDCAMS", "IEBGENER");
    /** ロードライブラリ指定のDD名。データセット参照辺の対象にしない。 */
    private static final Set<String> LIBRARY_DD_NAMES = Set.of("STEPLIB", "JOBLIB");

    // ノードIDの種別接頭辞
    private static final String PROGRAM_ID_PREFIX = "program:";
    private static final String JOB_ID_PREFIX = "job:";
    private static final String STEP_ID_PREFIX = "step:";
    private static final String DATASET_ID_PREFIX = "dataset:";
    private static final String UTILITY_ID_PREFIX = "utility:";
    private static final String UNRESOLVED_ID_PREFIX = "unresolved:";
    private static final String TRANSACTION_ID_PREFIX = "transaction:";
    private static final String BMS_MAP_ID_PREFIX = "bmsmap:";
    private static final String DB2_TABLE_ID_PREFIX = "db2:";

    private static final Pattern MOVE_LITERAL = Pattern.compile(
            "^MOVE\\s+(?:'([^']*)'|\"([^\"]*)\")\\s+TO\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final LinkerInput input;
    private final Map<String, CallGraphNode> nodes = new TreeMap<>();
    private final Set<CallGraphEdge> edges = new LinkedHashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Map<CallGraphEdge, Set<String>> dynamicCallVariables = new HashMap<>();
    private final Set<String> knownPrograms = new TreeSet<>();
    /** 出現したトランザクションIDと、finding位置に使う代表範囲(最初の出現)。 */
    private final Map<String, SourceRange> transactionRanges = new TreeMap<>();

    private CallGraphLinker(LinkerInput input) {
        this.input = input;
    }

    public static LinkResult link(LinkerInput input) {
        return new CallGraphLinker(input).build();
    }

    private LinkResult build() {
        for (CobolSemanticModel model : input.cobolModels()) {
            // 呼出先側(EXEC PGM=・CALL・XCTL等)と同じID生成規則(大文字化)で実体ノードを作る
            String programName = model.programId().toUpperCase(Locale.ROOT);
            knownPrograms.add(programName);
            putNode(new CallGraphNode(programId(programName), NodeKind.PROGRAM, programName));
        }
        for (BmsMapset mapset : input.mapsets()) {
            for (BmsMap map : mapset.maps()) {
                String qualified = mapset.name() + "." + map.name();
                putNode(new CallGraphNode(BMS_MAP_ID_PREFIX + qualified, NodeKind.BMS_MAP,
                        qualified));
            }
        }
        linkJobs();
        linkCalls();
        linkCics();
        resolveTransactions();
        linkDb2Tables();
        findings.sort(Comparator.comparing((Finding f) -> f.location().file())
                .thenComparingInt(f -> f.location().line())
                .thenComparingInt(f -> f.location().column())
                .thenComparing(Finding::ruleId)
                .thenComparing(Finding::message));
        return new LinkResult(new CallGraph(nodes.values(), edges), findings,
                dynamicCallVariables);
    }

    // ---- JCL: ジョブ・ステップ・EXEC PGM=・データセット参照 ----

    private void linkJobs() {
        for (JclJobModel job : input.jobs()) {
            String jobNodeId = JOB_ID_PREFIX + job.jobName();
            putNode(new CallGraphNode(jobNodeId, NodeKind.JOB, job.jobName()));
            for (JclStep step : job.steps()) {
                if (step.execKind() != JclExecKind.PGM) {
                    // PROC呼出ステップ自体はノードにしない(展開後のPGMステップが実体を担う)
                    continue;
                }
                String stepNodeId = STEP_ID_PREFIX + job.jobName() + "." + step.name();
                putNode(new CallGraphNode(stepNodeId, NodeKind.STEP, step.name()));
                addEdge(jobNodeId, stepNodeId, EdgeKind.EXECUTION, Resolution.CONSTANT);
                addEdge(stepNodeId, executionTargetNode(step.target()), EdgeKind.EXECUTION,
                        Resolution.CONSTANT);
                for (JclDdStatement dd : step.ddStatements()) {
                    if (LIBRARY_DD_NAMES.contains(dd.ddName().toUpperCase(Locale.ROOT))) {
                        continue;
                    }
                    dd.datasetName().ifPresent(dsn -> {
                        putNode(new CallGraphNode(DATASET_ID_PREFIX + dsn, NodeKind.DATASET, dsn));
                        addEdge(stepNodeId, DATASET_ID_PREFIX + dsn, EdgeKind.REFERENCE,
                                Resolution.CONSTANT);
                    });
                }
            }
        }
    }

    /** EXEC PGM= の対象名からノードを確保し、そのIDを返す(プログラム・外部ユーティリティ)。 */
    private String executionTargetNode(String target) {
        String name = target.toUpperCase(Locale.ROOT);
        if (EXTERNAL_UTILITIES.contains(name)) {
            String id = UTILITY_ID_PREFIX + name;
            putNode(new CallGraphNode(id, NodeKind.EXTERNAL_UTILITY, name,
                    Map.of("utility", name)));
            return id;
        }
        return ensureProgramNode(name);
    }

    /** プログラムノードを確保してIDを返す。意味モデルの無いものは外部プログラムとして型付けする。 */
    private String ensureProgramNode(String programName) {
        String id = programId(programName);
        if (!nodes.containsKey(id)) {
            putNode(new CallGraphNode(id, NodeKind.PROGRAM, programName,
                    knownPrograms.contains(programName) ? Map.of() : Map.of("external", "true")));
        }
        return id;
    }

    private static String programId(String programName) {
        return PROGRAM_ID_PREFIX + programName;
    }

    // ---- CALL: 静的・動的(定数伝播) ----

    private void linkCalls() {
        for (CobolSemanticModel model : input.cobolModels()) {
            Map<String, Set<String>> constantsByVariable = collectMoveConstants(model);
            String callerId = programId(model.programId().toUpperCase(Locale.ROOT));
            for (CallRelation call : model.calls()) {
                if (call.kind() == CallKind.STATIC) {
                    addEdge(callerId, ensureProgramNode(call.target().toUpperCase(Locale.ROOT)),
                            EdgeKind.CALL, Resolution.CONSTANT);
                    continue;
                }
                String variable = call.target();
                Set<String> candidates = constantsByVariable
                        .getOrDefault(variable.toUpperCase(Locale.ROOT), Set.of());
                if (candidates.isEmpty()) {
                    String unresolvedId = UNRESOLVED_ID_PREFIX + model.programId() + "." + variable;
                    putNode(new CallGraphNode(unresolvedId, NodeKind.UNRESOLVED, variable,
                            Map.of("variable", variable)));
                    addEdge(callerId, unresolvedId, EdgeKind.CALL, Resolution.UNRESOLVED);
                    findings.add(Finding.of(DYNAMIC_CALL_UNRESOLVED_RULE_ID, FindingLevel.NOTE,
                            "動的CALL(変数 " + variable + ")の呼出先を定数伝播で解決できない(未解決)",
                            call.range().start()));
                    continue;
                }
                for (String candidate : candidates) {
                    CallGraphEdge edge = new CallGraphEdge(callerId,
                            ensureProgramNode(candidate.toUpperCase(Locale.ROOT)), EdgeKind.CALL,
                            Resolution.CONSTANT);
                    addEdge(edge);
                    dynamicCallVariables.computeIfAbsent(edge, k -> new TreeSet<>())
                            .add(variable);
                    findings.add(Finding.of(DYNAMIC_CALL_RESOLVED_RULE_ID, FindingLevel.NOTE,
                            "動的CALL(変数 " + variable + ")を定数伝播で解決した"
                                    + "(定数由来。候補は分岐・実行順序を考慮しない全MOVE定数): "
                                    + model.programId() + " -> " + candidate,
                            call.range().start()));
                }
            }
        }
    }

    /**
     * EXEC CICS のオペランド値を、辺を張る対象の名前の集合へ解決する。値が当該プログラムの
     * データ項目名であれば変数指定であり、MOVE 定数伝播で解決する。定数を特定できない変数は
     * 空集合を返し、データ名でなければ定数指定としてそのまま返す。
     */
    private static Set<String> resolveCicsOperand(String operand, Set<String> dataNames,
            Map<String, Set<String>> constantsByVariable) {
        String upper = operand.toUpperCase(Locale.ROOT);
        if (!dataNames.contains(upper)) {
            return Set.of(operand);
        }
        return constantsByVariable.getOrDefault(upper, Set.of());
    }

    private static Set<String> dataItemNames(CobolSemanticModel model) {
        Set<String> names = new TreeSet<>();
        for (DataItem item : model.dataItems()) {
            names.add(item.name().toUpperCase(Locale.ROOT));
        }
        return names;
    }

    /** プログラム内の全MOVE文から「変数名(大文字化)→設定される定数リテラルの集合」を集める。 */
    private static Map<String, Set<String>> collectMoveConstants(CobolSemanticModel model) {
        Map<String, Set<String>> constants = new TreeMap<>();
        for (Procedure procedure : model.procedures()) {
            collectMoveConstants(procedure.statements(), constants);
        }
        return constants;
    }

    private static void collectMoveConstants(List<Statement> statements,
            Map<String, Set<String>> constants) {
        for (Statement statement : statements) {
            if (statement instanceof CompoundStatement compound) {
                for (StatementBlock block : compound.blocks()) {
                    collectMoveConstants(block.statements(), constants);
                }
                continue;
            }
            if (!(statement instanceof SimpleStatement simple)
                    || !"MOVE".equalsIgnoreCase(simple.verb())) {
                continue;
            }
            Matcher matcher = MOVE_LITERAL.matcher(simple.text().trim());
            if (!matcher.matches()) {
                continue;
            }
            String literal = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String[] tokens = matcher.group(3).trim().split("[,\\s]+");
            for (int i = 0; i < tokens.length; i++) {
                String name = stripTrailingPeriod(tokens[i]);
                // 修飾名(A OF B・A IN B)は先頭データ名で登録し、修飾部は読み飛ばす
                while (i + 2 < tokens.length && isQualifierKeyword(tokens[i + 1])) {
                    i += 2;
                }
                if (!name.isBlank()) {
                    constants.computeIfAbsent(name.toUpperCase(Locale.ROOT),
                            k -> new TreeSet<>()).add(literal);
                }
            }
        }
    }

    private static boolean isQualifierKeyword(String token) {
        String name = stripTrailingPeriod(token);
        return "OF".equalsIgnoreCase(name) || "IN".equalsIgnoreCase(name);
    }

    private static String stripTrailingPeriod(String token) {
        return token.endsWith(".") ? token.substring(0, token.length() - 1) : token;
    }

    // ---- EXEC CICS: 遷移辺・マップ参照辺・トランザクション解決 ----

    private void linkCics() {
        for (CobolSemanticModel model : input.cobolModels()) {
            String callerId = programId(model.programId().toUpperCase(Locale.ROOT));
            Map<String, Set<String>> constantsByVariable = collectMoveConstants(model);
            Set<String> dataNames = dataItemNames(model);
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                switch (block.kind()) {
                    case CICS_XCTL, CICS_LINK -> {
                        String target = block.operands().get("PROGRAM");
                        if (target != null) {
                            for (String resolved
                                    : resolveCicsOperand(target, dataNames, constantsByVariable)) {
                                addEdge(callerId,
                                        ensureProgramNode(resolved.toUpperCase(Locale.ROOT)),
                                        EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT);
                            }
                        }
                    }
                    case CICS_START, CICS_RETURN_TRANSID -> {
                        String operand = block.operands().get("TRANSID");
                        if (operand != null) {
                            for (String transId
                                    : resolveCicsOperand(operand, dataNames, constantsByVariable)) {
                                String id = TRANSACTION_ID_PREFIX + transId;
                                putNode(new CallGraphNode(id, NodeKind.TRANSACTION, transId));
                                addEdge(callerId, id, EdgeKind.TRANSACTION_TRANSITION,
                                        Resolution.CONSTANT);
                                transactionRanges.putIfAbsent(transId, block.range());
                            }
                        }
                    }
                    case CICS_SEND_MAP, CICS_RECEIVE_MAP -> {
                        String map = block.operands().get("MAP");
                        if (map != null) {
                            String mapset = block.operands().get("MAPSET");
                            String qualified = mapset != null ? mapset + "." + map : map;
                            String id = BMS_MAP_ID_PREFIX + qualified;
                            putNode(new CallGraphNode(id, NodeKind.BMS_MAP, qualified));
                            addEdge(callerId, id, EdgeKind.MAP_REFERENCE, Resolution.CONSTANT);
                        }
                    }
                    case SQL -> {
                        // SQLブロックはDb2表参照(linkDb2Tables)で扱う
                    }
                }
            }
        }
    }

    /** 出現した各トランザクションIDを定義表でプログラムへ解決し、解決辺を張る。 */
    private void resolveTransactions() {
        for (Map.Entry<String, SourceRange> entry : transactionRanges.entrySet()) {
            String transId = entry.getKey();
            String programName = input.programByTransactionId().get(transId);
            if (programName == null) {
                findings.add(Finding.of(TRANSACTION_UNRESOLVED_RULE_ID, FindingLevel.NOTE,
                        "トランザクションID " + transId + " をプログラムへ解決できない(定義表に無い)",
                        entry.getValue().start()));
                continue;
            }
            addEdge(TRANSACTION_ID_PREFIX + transId,
                    ensureProgramNode(programName.toUpperCase(Locale.ROOT)),
                    EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT);
        }
    }

    // ---- Db2表参照 ----

    private void linkDb2Tables() {
        for (Map.Entry<String, List<SqlStatementModel>> entry
                : new TreeMap<>(input.sqlStatementsByProgramId()).entrySet()) {
            String callerId = ensureProgramNode(entry.getKey().toUpperCase(Locale.ROOT));
            for (SqlStatementModel statement : entry.getValue()) {
                for (String table : statement.referencedTables()) {
                    putNode(new CallGraphNode(DB2_TABLE_ID_PREFIX + table, NodeKind.DB2_TABLE,
                            table));
                    addEdge(callerId, DB2_TABLE_ID_PREFIX + table, EdgeKind.REFERENCE,
                            Resolution.CONSTANT);
                }
            }
        }
    }

    // ---- 共通処理 ----

    private void putNode(CallGraphNode node) {
        nodes.putIfAbsent(node.id(), node);
    }

    private void addEdge(String fromId, String toId, EdgeKind kind, Resolution resolution) {
        addEdge(new CallGraphEdge(fromId, toId, kind, resolution));
    }

    private void addEdge(CallGraphEdge edge) {
        edges.add(edge);
    }
}
