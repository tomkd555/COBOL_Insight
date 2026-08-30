package jp.cobolinsight.analysis.linker;

import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.semantic.CallKind;
import jp.cobolinsight.core.semantic.CallRelation;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * Integrates the call graph. Builds a single {@link CallGraph} by matching JCL (EXEC PGM=) to
 * COBOL (PROGRAM-ID), resolving static CALLs, resolving dynamic CALLs by constant propagation
 * (MOVE constant -> CALL variable), typing unresolved nodes and external-utility nodes,
 * creating EXEC CICS transaction-transition edges and map-reference edges, and resolving
 * transaction ID -> program via the transaction definition table. Node IDs are determined
 * deterministically as strings with a kind-specific prefix.
 */
public final class CallGraphLinker {

    /** Rule ID that records the resolution basis (constant-derived) when a dynamic CALL is resolved by constant propagation. */
    public static final String DYNAMIC_CALL_RESOLVED_RULE_ID = "callgraph-dynamic-call";
    /** Rule ID that records that a dynamic CALL could not be resolved. */
    public static final String DYNAMIC_CALL_UNRESOLVED_RULE_ID = "callgraph-dynamic-call-unresolved";
    /** Rule ID that records that a transaction ID could not be resolved to a program. */
    public static final String TRANSACTION_UNRESOLVED_RULE_ID = "callgraph-transaction-unresolved";

    private static final Set<String> EXTERNAL_UTILITIES = Set.of("DFSORT", "IDCAMS", "IEBGENER");
    /** DD names that designate a load library. Excluded from being the target of a dataset-reference edge. */
    private static final Set<String> LIBRARY_DD_NAMES = Set.of("STEPLIB", "JOBLIB");

    // Kind-specific prefixes for node IDs
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
    /** Transaction IDs encountered, with the representative range (first occurrence) used for finding locations. */
    private final Map<String, SourceRange> transactionRanges = new TreeMap<>();

    private CallGraphLinker(LinkerInput input) {
        this.input = input;
    }

    public static LinkResult link(LinkerInput input) {
        return new CallGraphLinker(input).build();
    }

    private LinkResult build() {
        for (CobolSemanticModel model : input.cobolModels()) {
            // Create the entity node using the same ID-generation rule (uppercasing) as the callee side (EXEC PGM=, CALL, XCTL, etc.)
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
        return new LinkResult(new CallGraph(nodes.values(), numberBySourceOrder(edges)), findings,
                dynamicCallVariables);
    }

    // ---- JCL: job, step, EXEC PGM=, dataset reference ----

    private void linkJobs() {
        for (JclJobModel job : input.jobs()) {
            String jobNodeId = JOB_ID_PREFIX + job.jobName();
            putNode(new CallGraphNode(jobNodeId, NodeKind.JOB, job.jobName()));
            for (JclStep step : job.steps()) {
                if (step.execKind() != JclExecKind.PGM) {
                    // A PROC-invoking step itself is not made a node (the expanded PGM step carries the substance)
                    continue;
                }
                String stepNodeId = STEP_ID_PREFIX + job.jobName() + "." + step.name();
                putNode(new CallGraphNode(stepNodeId, NodeKind.STEP, step.name()));
                addEdge(jobNodeId, stepNodeId, EdgeKind.EXECUTION, Resolution.CONSTANT,
                        step.position().line());
                addEdge(stepNodeId, executionTargetNode(step.target()), EdgeKind.EXECUTION,
                        Resolution.CONSTANT, step.position().line());
                for (JclDdStatement dd : step.ddStatements()) {
                    if (LIBRARY_DD_NAMES.contains(dd.ddName().toUpperCase(Locale.ROOT))) {
                        continue;
                    }
                    dd.datasetName().ifPresent(dsn -> {
                        putNode(new CallGraphNode(DATASET_ID_PREFIX + dsn, NodeKind.DATASET, dsn));
                        addEdge(stepNodeId, DATASET_ID_PREFIX + dsn, EdgeKind.REFERENCE,
                                Resolution.CONSTANT, dd.position().line());
                    });
                }
            }
        }
    }

    /** Ensures a node for the EXEC PGM= target name and returns its ID (a program or an external utility). */
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

    /** Ensures a program node and returns its ID. One without a semantic model is typed as an external program. */
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

    // ---- CALL: static and dynamic (constant propagation) ----

    private void linkCalls() {
        for (CobolSemanticModel model : input.cobolModels()) {
            Map<String, Set<String>> constantsByVariable = collectMoveConstants(model);
            String callerId = programId(model.programId().toUpperCase(Locale.ROOT));
            for (CallRelation call : model.calls()) {
                if (call.kind() == CallKind.STATIC) {
                    addEdge(callerId, ensureProgramNode(call.target().toUpperCase(Locale.ROOT)),
                            EdgeKind.CALL, Resolution.CONSTANT, call.range().start().line());
                    continue;
                }
                String variable = call.target();
                Set<String> candidates = constantsByVariable
                        .getOrDefault(variable.toUpperCase(Locale.ROOT), Set.of());
                if (candidates.isEmpty()) {
                    String unresolvedId = UNRESOLVED_ID_PREFIX + model.programId() + "." + variable;
                    putNode(new CallGraphNode(unresolvedId, NodeKind.UNRESOLVED, variable,
                            Map.of("variable", variable)));
                    addEdge(callerId, unresolvedId, EdgeKind.CALL, Resolution.UNRESOLVED,
                            call.range().start().line());
                    findings.add(Finding.of(DYNAMIC_CALL_UNRESOLVED_RULE_ID, FindingLevel.NOTE,
                            "動的CALL(変数 " + variable + ")の呼出先を定数伝播で解決できない(未解決)",
                            call.range().start()));
                    continue;
                }
                for (String candidate : candidates) {
                    CallGraphEdge edge = addEdge(callerId,
                            ensureProgramNode(candidate.toUpperCase(Locale.ROOT)), EdgeKind.CALL,
                            Resolution.CONSTANT, call.range().start().line());
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
     * Resolves an EXEC CICS operand value into the set of names to draw an edge to. If the
     * value is a data item name in the program, it is a variable reference and is resolved by
     * MOVE constant propagation. A variable whose constant cannot be identified returns an
     * empty set; a value that is not a data name is a literal and is returned as-is.
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

    /** Collects, from every MOVE statement in the program, "variable name (uppercased) -> the set of constant literals it is set to". */
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
                // A qualified name (A OF B, A IN B) is registered under its leading data name, and the qualifier part is skipped over
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

    // ---- EXEC CICS: transition edges, map-reference edges, transaction resolution ----

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
                                        EdgeKind.TRANSACTION_TRANSITION, Resolution.CONSTANT,
                                        block.range().start().line());
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
                                        Resolution.CONSTANT, block.range().start().line());
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
                            addEdge(callerId, id, EdgeKind.MAP_REFERENCE, Resolution.CONSTANT,
                                    block.range().start().line());
                        }
                    }
                    case SQL -> {
                        // An SQL block is handled by the Db2 table reference logic (linkDb2Tables)
                    }
                }
            }
        }
    }

    /** Resolves each transaction ID encountered to a program via the definition table, and draws a resolution edge. */
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

    // ---- Db2 table reference ----

    private void linkDb2Tables() {
        for (Map.Entry<String, List<SqlStatementModel>> entry
                : new TreeMap<>(input.sqlStatementsByProgramId()).entrySet()) {
            String callerId = ensureProgramNode(entry.getKey().toUpperCase(Locale.ROOT));
            for (SqlStatementModel statement : entry.getValue()) {
                for (String table : statement.referencedTables()) {
                    putNode(new CallGraphNode(DB2_TABLE_ID_PREFIX + table, NodeKind.DB2_TABLE,
                            table));
                    addEdge(callerId, DB2_TABLE_ID_PREFIX + table, EdgeKind.REFERENCE,
                            Resolution.CONSTANT, statement.range().start().line());
                }
            }
        }
    }

    // ---- Common processing ----

    private void putNode(CallGraphNode node) {
        nodes.putIfAbsent(node.id(), node);
    }

    private CallGraphEdge addEdge(String fromId, String toId, EdgeKind kind,
            Resolution resolution) {
        return addEdge(fromId, toId, kind, resolution, null);
    }

    /**
     * Adds one edge and returns it. seq is left at 0 here, since {@link #numberBySourceOrder}
     * assigns it in bulk at the end. A previously seen edge is folded into one, keeping the
     * line from its first occurrence.
     */
    private CallGraphEdge addEdge(String fromId, String toId, EdgeKind kind, Resolution resolution,
            Integer line) {
        CallGraphEdge edge = new CallGraphEdge(fromId, toId, kind, resolution, 0, line);
        edges.add(edge);
        return edge;
    }

    /**
     * For each caller node, renumbers seq from 1 in source order (ascending by the call site's
     * line, with an edge whose line is unknown placed after those). The logic that adds edges
     * is split across JCL, CALL, EXEC CICS, and Db2 tables, so left in add order the edges from
     * the same caller would not appear in source order (CICS's XCTL would come after a CALL on
     * a later line).
     *
     * <p>The ordering of rows (the set's iteration order) is left unchanged, in add order.
     * Persistence assigns row IDs in that order.
     */
    private static Set<CallGraphEdge> numberBySourceOrder(Set<CallGraphEdge> edges) {
        Map<String, List<CallGraphEdge>> byFrom = new LinkedHashMap<>();
        for (CallGraphEdge edge : edges) {
            byFrom.computeIfAbsent(edge.fromId(), k -> new ArrayList<>()).add(edge);
        }
        Map<CallGraphEdge, Integer> seqByEdge = new HashMap<>();
        for (List<CallGraphEdge> group : byFrom.values()) {
            group.sort(Comparator.comparing(CallGraphEdge::line,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            int seq = 0;
            for (CallGraphEdge edge : group) {
                seqByEdge.put(edge, ++seq);
            }
        }
        Set<CallGraphEdge> numbered = new LinkedHashSet<>();
        for (CallGraphEdge edge : edges) {
            numbered.add(new CallGraphEdge(edge.fromId(), edge.toId(), edge.kind(),
                    edge.resolution(), seqByEdge.get(edge), edge.line()));
        }
        return numbered;
    }
}
