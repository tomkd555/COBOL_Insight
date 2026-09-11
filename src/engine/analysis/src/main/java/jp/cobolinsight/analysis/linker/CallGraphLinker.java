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
import jp.cobolinsight.core.jcl.JclDataset;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.jcl.JclUtilityFacts;
import jp.cobolinsight.core.jcl.JclUtilityFacts.DatasetAccess;
import jp.cobolinsight.core.semantic.CallKind;
import jp.cobolinsight.core.semantic.CallRelation;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.CompoundStatement;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.FileAccess;
import jp.cobolinsight.core.semantic.FileDefinition;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.semantic.Statement;
import jp.cobolinsight.core.semantic.StatementBlock;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integrates the call graph. Builds a single {@link CallGraph} by matching JCL (EXEC PGM=) to
 * COBOL (PROGRAM-ID), resolving static CALLs, resolving dynamic CALLs by constant propagation
 * (MOVE constant -> CALL variable), typing unresolved nodes and external-utility nodes,
 * creating EXEC CICS transaction-transition edges and map-reference edges, resolving
 * transaction ID -> program via the transaction definition table, and taking in what an SQL script
 * declares (a table with its column count, a procedure, function or trigger as a program of its
 * own). Node IDs are determined deterministically as strings with a kind-specific prefix.
 */
public final class CallGraphLinker {

    /** Rule ID that records the resolution basis (constant-derived) when a dynamic CALL is resolved by constant propagation. */
    public static final String DYNAMIC_CALL_RESOLVED_RULE_ID = "callgraph-dynamic-call";
    /** Rule ID that records that a dynamic CALL could not be resolved. */
    public static final String DYNAMIC_CALL_UNRESOLVED_RULE_ID = "callgraph-dynamic-call-unresolved";
    /** Rule ID that records that a transaction ID could not be resolved to a program. */
    public static final String TRANSACTION_UNRESOLVED_RULE_ID = "callgraph-transaction-unresolved";

    /** The resource listing the EXEC PGM= targets that are system utilities, not applications. */
    private static final String UTILITIES_RESOURCE = "utilities.txt";

    /**
     * EXEC PGM= targets that are system utilities rather than application programs: the sort
     * products, the IEB/IDC/IEH utilities, IEFBR14, the Db2 utilities and the TSO batch monitor
     * under which a Db2 program is run. Read once from {@link #UTILITIES_RESOURCE}.
     */
    private static final Set<String> EXTERNAL_UTILITIES = readUtilities();

    private static Set<String> readUtilities() {
        Set<String> names = new TreeSet<>();
        try (InputStream in = CallGraphLinker.class.getResourceAsStream(UTILITIES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource: " + UTILITIES_RESOURCE);
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String name = line.strip();
                if (!name.isEmpty() && !name.startsWith("#")) {
                    names.add(name.toUpperCase(Locale.ROOT));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + UTILITIES_RESOURCE, e);
        }
        return Set.copyOf(names);
    }

    /** DD names that designate a load library. Excluded from being the target of a dataset-reference edge. */
    private static final Set<String> LIBRARY_DD_NAMES = Set.of("STEPLIB", "JOBLIB");

    // Kind-specific prefixes for node IDs
    /** Node id prefix for a program. Public so persistence can tell a program node from any other. */
    public static final String PROGRAM_ID_PREFIX = "program:";
    /** Node id prefix for a routine an SQL script defines, kept clear of the program space. */
    public static final String SQL_ROUTINE_ID_PREFIX = "sqlroutine:";
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
    private static final Pattern QUOTED_LITERAL = Pattern.compile("^(?:'([^']*)'|\"([^\"]*)\")$");

    private final LinkerInput input;
    private final Map<String, CallGraphNode> nodes = new TreeMap<>();
    /**
     * The edges by their own identity, which ignores attributes. Keeping the stored edge reachable
     * is what lets a second edge between the same endpoints merge its attributes into the first
     * instead of being dropped whole.
     */
    private final Map<CallGraphEdge, CallGraphEdge> edges = new LinkedHashMap<>();
    private final List<Finding> findings = new ArrayList<>();
    private final Map<CallGraphEdge, Set<String>> dynamicCallVariables = new HashMap<>();
    private final Set<String> knownPrograms = new TreeSet<>();
    /** The analysed programs by PROGRAM-ID (uppercased), for the FILE-CONTROL entries of a step. */
    private final Map<String, CobolSemanticModel> modelsByProgram = new TreeMap<>();
    /** Mapset names whose BMS source was analysed; a map missing from one of them is marked undefined. */
    private final Set<String> knownMapsets = new TreeSet<>();
    /** Transaction IDs encountered, with the representative range (first occurrence) used for finding locations. */
    private final Map<String, SourceRange> transactionRanges = new TreeMap<>();
    /** The routines the SQL scripts define, by their name without the schema qualifier, uppercased. */
    private final Set<String> knownRoutines = new TreeSet<>();

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
            modelsByProgram.putIfAbsent(programName, model);
            putNode(new CallGraphNode(programId(programName), NodeKind.PROGRAM, programName));
        }
        for (BmsMapset mapset : input.mapsets()) {
            knownMapsets.add(mapset.name().toUpperCase(Locale.ROOT));
            for (BmsMap map : mapset.maps()) {
                String qualified = mapset.name() + "." + map.name();
                putNode(new CallGraphNode(BMS_MAP_ID_PREFIX + qualified, NodeKind.BMS_MAP,
                        qualified));
            }
        }
        // Before the rest: a node keeps the attributes of whoever created it, and what a script
        // declares about a table or a routine is more than any reference to it says.
        linkSqlScripts();
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
        return new LinkResult(new CallGraph(nodes.values(), numberBySourceOrder(edges.values())),
                findings, dynamicCallVariables);
    }

    // ---- JCL: job, step, EXEC PGM=, dataset reference ----

    /**
     * Job, step, execution target and data set edges, with how the step uses each data set on the
     * edge as {@code access}.
     *
     * <p>Which access wins, where more than one applies: the FILE-CONTROL of the program that reads
     * the step's DD statements beats the step's own control cards and the DISP of the DD, because it
     * says what the program does rather than what the JCL allows. Where two DD statements of one
     * step, or a DD and a control card, name the same data set, reading through one and writing
     * through the other makes it {@code UPDATE}; any other pair keeps the access of the DD the
     * source writes first. Where two SELECT entries of the program name the same DD, the modes of
     * both are taken together before any of this.
     */
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
                Map<String, String> ddNamesOfProgram = ddAccessByDdName(step);
                for (JclDdStatement dd : step.ddStatements()) {
                    if (LIBRARY_DD_NAMES.contains(dd.ddName().toUpperCase(Locale.ROOT))) {
                        continue;
                    }
                    datasetOf(dd).ifPresent(dsn -> addEdge(stepNodeId, datasetNode(dsn),
                            EdgeKind.REFERENCE, Resolution.CONSTANT, dd.position().line(),
                            accessAttribute(accessOf(dd, step, ddNamesOfProgram))));
                }
                step.utility().ifPresent(facts -> linkUtilityFacts(step, stepNodeId, facts));
            }
        }
    }

    /** What a DSN written as a referback begins with, before anything resolves it. */
    private static final String REFERBACK_PREFIX = "*.";

    /**
     * The data set a DD names, member and relative generation written back the way the DSN
     * parameter spells them, so the node keeps the name a reader would search the JCL for. A
     * referback resolves to the data set the referenced DD names.
     *
     * <p>Empty where the DD names no data set the job can point at. A referback the job could not
     * resolve is one of those: the DD it names is written nowhere, so the text stays {@code
     * *.step.dd} and there is no data set behind it. Making a node of that text would put a data
     * set in the graph that no one can find, and join every step whose referback missed in the same
     * way to it. The DD keeps the text in JCL_DD, which records what the JCL states.
     */
    private static Optional<String> datasetOf(JclDdStatement dd) {
        return datasetTextOf(dd).filter(text -> !text.startsWith(REFERBACK_PREFIX));
    }

    private static Optional<String> datasetTextOf(JclDdStatement dd) {
        if (dd.dataset().isEmpty()) {
            return dd.datasetName();
        }
        JclDataset dataset = dd.dataset().orElseThrow();
        if (dataset.member().isPresent()) {
            return Optional.of(dataset.name() + "(" + dataset.member().orElseThrow() + ")");
        }
        return Optional.of(dataset.gdgRelative()
                .map(generation -> dataset.name() + "("
                        + (generation > 0 ? "+" + generation : String.valueOf(generation)) + ")")
                .orElseGet(dataset::name));
    }

    /** Ensures a data set node and returns its ID. */
    private String datasetNode(String dsn) {
        putNode(new CallGraphNode(DATASET_ID_PREFIX + dsn, NodeKind.DATASET, dsn));
        return DATASET_ID_PREFIX + dsn;
    }

    /**
     * DD name (uppercased) to the access the program reading the step's DD statements makes of it.
     * That is the step's own EXEC target as a rule; for a launcher step it is the one program a
     * {@code RUN PROGRAM} card names, since the launcher itself reads none of the DDs. A step whose
     * cards run several programs falls back to the DISP, because which of them reads which DD is
     * not stated anywhere.
     */
    private Map<String, String> ddAccessByDdName(JclStep step) {
        Map<String, String> own = ddNamesOfProgram(step.target());
        if (!own.isEmpty()) {
            return own;
        }
        List<JclUtilityFacts.ProgramRun> runs =
                step.utility().map(JclUtilityFacts::programRuns).orElseGet(List::of);
        return runs.size() == 1 ? ddNamesOfProgram(runs.get(0).program()) : Map.of();
    }

    /**
     * DD name (uppercased) to the access one program makes of it, from its FILE-CONTROL. Two SELECT
     * entries may name the same DD, so the modes of every entry naming it are taken together.
     */
    private Map<String, String> ddNamesOfProgram(String target) {
        CobolSemanticModel model = modelsByProgram.get(target.toUpperCase(Locale.ROOT));
        if (model == null) {
            return Map.of();
        }
        Map<String, Set<FileAccess>> modesByDdName = new TreeMap<>();
        for (FileDefinition file : model.files()) {
            file.ddName().ifPresent(ddName -> modesByDdName
                    .computeIfAbsent(ddName.toUpperCase(Locale.ROOT),
                            key -> EnumSet.noneOf(FileAccess.class))
                    .addAll(file.accesses()));
        }
        Map<String, String> byDdName = new TreeMap<>();
        modesByDdName.forEach((ddName, modes) ->
                accessOf(modes).ifPresent(access -> byDdName.put(ddName, access)));
        return byDdName;
    }

    /**
     * What the OPEN modes of a file amount to: reading, writing, or both at once, which is what
     * I-O means and what opening the same file for input and for output comes to as well.
     */
    private static Optional<String> accessOf(Set<FileAccess> modes) {
        boolean writes = modes.contains(FileAccess.OUTPUT) || modes.contains(FileAccess.EXTEND);
        if (modes.contains(FileAccess.IO) || (writes && modes.contains(FileAccess.INPUT))) {
            return Optional.of(DatasetAccess.UPDATE.name());
        }
        if (writes) {
            return Optional.of(DatasetAccess.WRITE.name());
        }
        return modes.contains(FileAccess.INPUT)
                ? Optional.of(DatasetAccess.READ.name()) : Optional.empty();
    }

    /**
     * How the step uses one DD: what the program's FILE-CONTROL says, and failing that what the
     * step's own control cards and the DISP of the DD said (both already in {@code ddRoles}).
     */
    private static Optional<String> accessOf(JclDdStatement dd, JclStep step,
            Map<String, String> ddNamesOfProgram) {
        String ddName = dd.ddName().toUpperCase(Locale.ROOT);
        String fromProgram = ddNamesOfProgram.get(ddName);
        if (fromProgram != null) {
            return Optional.of(fromProgram);
        }
        return step.utility().map(facts -> facts.ddRoles().get(ddName))
                .map(DatasetAccess::name);
    }

    /** The attribute key that says how the origin of an edge uses its target. */
    private static final String ACCESS_ATTRIBUTE = "access";

    private static Map<String, String> accessAttribute(Optional<String> access) {
        return access.map(value -> Map.of(ACCESS_ATTRIBUTE, value)).orElseGet(Map::of);
    }

    /**
     * The edges the step's own control cards state: the program a TSO launcher runs, the plans and
     * packages a BIND names, and the data sets and Db2 tables a card names without a DD statement.
     */
    private void linkUtilityFacts(JclStep step, String stepNodeId, JclUtilityFacts facts) {
        int line = step.position().line();
        for (JclUtilityFacts.ProgramRun run : facts.programRuns()) {
            Map<String, String> attributes = new TreeMap<>();
            attributes.put("launcher", step.target().toUpperCase(Locale.ROOT));
            run.plan().ifPresent(plan -> attributes.put("plan", plan));
            // The card may name a utility (DSNTEP2 under IKJEFT01) as readily as an
            // application, and it is the same kind of target an EXEC PGM= names.
            addEdge(stepNodeId, executionTargetNode(run.program()), EdgeKind.EXECUTION,
                    Resolution.CONSTANT, line, attributes);
        }
        for (JclUtilityFacts.BindRequest bind : facts.binds()) {
            Map<String, String> attributes = Map.of(
                    "bind", bind.kind().endsWith("PACKAGE") ? "PACKAGE" : "PLAN",
                    "plan", bind.name());
            for (String member : bind.members()) {
                addEdge(stepNodeId, ensureProgramNode(member.toUpperCase(Locale.ROOT)),
                        EdgeKind.REFERENCE, Resolution.CONSTANT, line, attributes);
            }
        }
        for (JclUtilityFacts.DatasetUse use : facts.datasetUses()) {
            addEdge(stepNodeId, datasetNode(use.dataset()), EdgeKind.REFERENCE,
                    Resolution.CONSTANT, line, Map.of(ACCESS_ATTRIBUTE, use.access().name()));
        }
        for (JclUtilityFacts.TableUse use : facts.tableUses()) {
            addEdge(stepNodeId, db2TableNode(use.table()), EdgeKind.REFERENCE,
                    Resolution.CONSTANT, line, Map.of(ACCESS_ATTRIBUTE, use.access().name()));
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
                    programAttributes(programName)));
        }
        return id;
    }

    /**
     * What a program node carries. A name this run analysed carries nothing; one it did not is
     * external. On a scoped run every external name carries {@code outsideScope} as well: the
     * scope left part of the COBOL unread, so nothing here can tell "nobody has this source" from
     * "this run was not asked for it", and a rule about unanalysed callees must not guess.
     */
    private Map<String, String> programAttributes(String programName) {
        if (knownPrograms.contains(programName)) {
            return Map.of();
        }
        return input.scoped()
                ? Map.of("external", "true", "outsideScope", "true")
                : Map.of("external", "true");
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
                                    + "(定数由来。候補は分岐・実行順序を考慮しない全MOVE定数と VALUE 句): "
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

    /**
     * Collects "variable name (uppercased) -> the set of constant literals it is set to" from
     * every MOVE statement in the program and from the VALUE clause of every data item. A
     * program name held in a VALUE and never reassigned is the common field idiom for a
     * dynamic CALL.
     */
    private static Map<String, Set<String>> collectMoveConstants(CobolSemanticModel model) {
        Map<String, Set<String>> constants = new TreeMap<>();
        collectValueConstants(model.dataItems(), constants);
        for (Procedure procedure : model.procedures()) {
            collectMoveConstants(procedure.statements(), constants);
        }
        return constants;
    }

    private static void collectValueConstants(List<DataItem> items,
            Map<String, Set<String>> constants) {
        for (DataItem item : items) {
            item.value().ifPresent(value -> {
                Matcher matcher = QUOTED_LITERAL.matcher(value.trim());
                if (matcher.matches()) {
                    constants.computeIfAbsent(item.name().toUpperCase(Locale.ROOT),
                            k -> new TreeSet<>()).add(
                                    matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                }
            });
            collectValueConstants(item.children(), constants);
        }
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
                            // LINK returns to the caller, so it is a call; XCTL hands control over
                            EdgeKind kind = block.kind() == EmbeddedBlockKind.CICS_LINK
                                    ? EdgeKind.CALL : EdgeKind.TRANSACTION_TRANSITION;
                            for (String resolved
                                    : resolveCicsOperand(target, dataNames, constantsByVariable)) {
                                addEdge(callerId,
                                        ensureProgramNode(resolved.toUpperCase(Locale.ROOT)),
                                        kind, Resolution.CONSTANT,
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
                            // The mapset was analysed but has no such map: R031's case, marked on the node
                            boolean undefined = mapset != null && !nodes.containsKey(id)
                                    && knownMapsets.contains(mapset.toUpperCase(Locale.ROOT));
                            putNode(new CallGraphNode(id, NodeKind.BMS_MAP, qualified,
                                    undefined ? Map.of("undefined", "true") : Map.of()));
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

    // ---- SQL scripts: declared tables and the routines a script defines ----

    /**
     * What an SQL script contributes. A {@code CREATE TABLE} names a table, so its Db2 table node
     * carries which script declares it and, where the grammar read the declaration in full, how many
     * columns it holds. A procedure, function or trigger runs statements, so it becomes a node of its
     * own with a reference edge to every table its body touches and the CRUD letters of that access
     * on the edge. Every other statement of a script — the DML a SPUFI member runs, a GRANT, a
     * COMMENT — contributes no edge: nothing runs it but the person who submitted the member.
     *
     * <p>A routine keeps its own id space. A routine and a COBOL program may carry one name without
     * being one thing, and two scripts may declare the same routine; sharing the program space would
     * merge the first pair and collapse the second, and the graph would show a call reaching a
     * program that never had that code.
     */
    private void linkSqlScripts() {
        Map<String, List<SqlRoutineDefinition>> byName = new TreeMap<>();
        for (SqlRoutineDefinition routine : input.sqlRoutines()) {
            byName.computeIfAbsent(routine.unqualifiedName().toUpperCase(Locale.ROOT),
                    key -> new ArrayList<>()).add(routine);
        }
        byName.forEach((name, routines) -> {
            Set<String> definers = new LinkedHashSet<>();
            routines.forEach(routine -> definers.add(routine.definedIn()));
            knownRoutines.add(name);
            putNode(new CallGraphNode(sqlRoutineId(name), NodeKind.PROGRAM, name,
                    Map.of("sqlProcedure", "true", "definedIn", String.join(",", definers))));
        });
        for (Map.Entry<String, List<SqlStatementModel>> script
                : new TreeMap<>(input.sqlStatementsByScript()).entrySet()) {
            for (SqlStatementModel statement : script.getValue()) {
                if (statement.kind() != SqlStatementKind.DDL) {
                    continue;
                }
                statement.declaredTable()
                        .ifPresent(table -> db2TableNode(table,
                                declarationAttributes(script.getKey(), statement)));
            }
        }
        for (SqlRoutineDefinition routine : input.sqlRoutines()) {
            String callerId = sqlRoutineId(routine.unqualifiedName().toUpperCase(Locale.ROOT));
            Map<String, String> lettersByTable = accessLetters(routine.body());
            for (SqlStatementModel statement : routine.body()) {
                for (String table : statement.referencedTables()) {
                    String letters = lettersByTable.get(table.toUpperCase(Locale.ROOT));
                    addEdge(callerId, db2TableNode(table), EdgeKind.REFERENCE,
                            Resolution.CONSTANT, statement.range().start().line(),
                            letters == null ? Map.of() : Map.of(ACCESS_ATTRIBUTE, letters));
                }
            }
        }
    }

    /** What a declaration says about its table: which script holds it, and its column count. */
    private static Map<String, String> declarationAttributes(String script,
            SqlStatementModel statement) {
        Map<String, String> attributes = new TreeMap<>();
        attributes.put("definedIn", script);
        if (!statement.declaredColumns().isEmpty()) {
            // A declaration the grammar refused names its table and no column, and a count of zero
            // would read as a table declared without columns rather than as one nobody counted.
            attributes.put("columns", String.valueOf(statement.declaredColumns().size()));
        }
        return attributes;
    }

    /**
     * Ensures the node of one Db2 table and returns its id. The id is uppercased, so a declaration
     * and a reference that spell the schema differently meet on one node; the label keeps the
     * spelling the source wrote, and the first source to name the table decides it.
     */
    private String db2TableNode(String table, Map<String, String> attributes) {
        String id = DB2_TABLE_ID_PREFIX + table.toUpperCase(Locale.ROOT);
        putNode(new CallGraphNode(id, NodeKind.DB2_TABLE, table, attributes));
        return id;
    }

    private String db2TableNode(String table) {
        return db2TableNode(table, Map.of());
    }

    private static String sqlRoutineId(String name) {
        return SQL_ROUTINE_ID_PREFIX + name;
    }

    /**
     * The node a CALL's target names: the routine a script of this walk defines, matched on its name
     * without the schema qualifier, because a CALL and a CREATE PROCEDURE routinely disagree about
     * the qualifier while naming the same routine. A target no script defines is a program, as it has
     * always been.
     */
    private String calledProcedureNode(String procedure) {
        String unqualified = procedure.substring(procedure.lastIndexOf('.') + 1)
                .toUpperCase(Locale.ROOT);
        return knownRoutines.contains(unqualified) ? sqlRoutineId(unqualified)
                : ensureProgramNode(procedure.toUpperCase(Locale.ROOT));
    }

    // ---- Db2 table reference ----

    private void linkDb2Tables() {
        for (Map.Entry<String, List<SqlStatementModel>> entry
                : new TreeMap<>(input.sqlStatementsByProgramId()).entrySet()) {
            String callerId = ensureProgramNode(entry.getKey().toUpperCase(Locale.ROOT));
            Map<String, String> lettersByTable = accessLetters(entry.getValue());
            for (SqlStatementModel statement : entry.getValue()) {
                for (String table : statement.referencedTables()) {
                    String letters = lettersByTable.get(table.toUpperCase(Locale.ROOT));
                    addEdge(callerId, db2TableNode(table), EdgeKind.REFERENCE,
                            Resolution.CONSTANT, statement.range().start().line(),
                            letters == null ? Map.of() : Map.of(ACCESS_ATTRIBUTE, letters));
                }
                // An SQL CALL runs a stored procedure, which is a program like any other callee.
                statement.procedureName().ifPresent(procedure -> addEdge(callerId,
                        calledProcedureNode(procedure), EdgeKind.CALL,
                        Resolution.CONSTANT, statement.range().start().line(),
                        Map.of("sqlProcedure", "true")));
            }
        }
    }

    /** The order the CRUD letters of a table are written in, whatever order the statements read. */
    private static final String ACCESS_LETTERS = "RCUD";

    /**
     * Table (uppercased) to the letters every statement of one program accesses it with, R, C, U
     * and D in that order. A dynamic statement books its access under the table name {@code ?},
     * which no edge looks up; what it touches is persisted per statement in SQL_TABLE_USE alone.
     */
    private static Map<String, String> accessLetters(List<SqlStatementModel> statements) {
        Map<String, Set<Character>> byTable = new TreeMap<>();
        for (SqlStatementModel statement : statements) {
            statement.tableAccess().forEach((table, letters) -> {
                Set<Character> collected = byTable
                        .computeIfAbsent(table.toUpperCase(Locale.ROOT), k -> new TreeSet<>());
                for (char letter : letters.toCharArray()) {
                    collected.add(letter);
                }
            });
        }
        Map<String, String> ordered = new TreeMap<>();
        byTable.forEach((table, letters) -> {
            StringBuilder text = new StringBuilder();
            for (char letter : ACCESS_LETTERS.toCharArray()) {
                if (letters.contains(letter)) {
                    text.append(letter);
                }
            }
            if (text.length() > 0) {
                ordered.put(table, text.toString());
            }
        });
        return ordered;
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
        return addEdge(fromId, toId, kind, resolution, line, Map.of());
    }

    /**
     * The same, with the auxiliary information the edge carries. An edge already seen keeps its
     * line and takes the new attributes into the ones it holds, so the second reason for the same
     * edge — a step binding one program as a plan and as a package, a data set a step reads through
     * one DD and writes through another — is not lost with the edge that carried it.
     */
    private CallGraphEdge addEdge(String fromId, String toId, EdgeKind kind, Resolution resolution,
            Integer line, Map<String, String> attributes) {
        CallGraphEdge edge =
                new CallGraphEdge(fromId, toId, kind, resolution, 0, line, attributes);
        CallGraphEdge seen = edges.get(edge);
        if (seen == null) {
            edges.put(edge, edge);
            return edge;
        }
        if (attributes.isEmpty()) {
            return seen;
        }
        CallGraphEdge merged = new CallGraphEdge(seen.fromId(), seen.toId(), seen.kind(),
                seen.resolution(), seen.seq(), seen.line(),
                mergedAttributes(seen.attributes(), attributes));
        edges.put(merged, merged);
        return merged;
    }

    /** The attribute keys whose values accumulate rather than replace one another. */
    private static final Set<String> JOINED_ATTRIBUTES = Set.of("plan", "bind", "launcher");

    /**
     * The attributes of an edge once a second reason for it is known. A plan, a bind kind and a
     * launcher accumulate, in the order they were read; an access that is a read on one side and a
     * write on the other becomes an update; every other key keeps what it had.
     */
    private static Map<String, String> mergedAttributes(Map<String, String> kept,
            Map<String, String> added) {
        Map<String, String> merged = new TreeMap<>(kept);
        added.forEach((key, value) -> {
            String held = merged.get(key);
            if (held == null) {
                merged.put(key, value);
            } else if (JOINED_ATTRIBUTES.contains(key)) {
                merged.put(key, joinedDistinct(held, value));
            } else if (ACCESS_ATTRIBUTE.equals(key)) {
                merged.put(key, combinedAccess(held, value));
            }
        });
        return merged;
    }

    private static String joinedDistinct(String kept, String added) {
        Set<String> values = new LinkedHashSet<>(List.of(kept.split(",")));
        values.addAll(List.of(added.split(",")));
        return String.join(",", values);
    }

    /**
     * Reading a data set through one DD of a step and writing it through another is an update of it,
     * and so is any pair one side of which is already an update — an update read beside an update
     * written is still an update. Any other pair keeps the access the source states first, because
     * nothing says the two describe the same use: a step that deletes a data set through a control
     * card and reads it through a DD stays a delete.
     */
    private static String combinedAccess(String kept, String added) {
        String update = DatasetAccess.UPDATE.name();
        if (update.equals(kept) || update.equals(added)) {
            return update;
        }
        boolean read = DatasetAccess.READ.name().equals(kept)
                || DatasetAccess.READ.name().equals(added);
        boolean write = DatasetAccess.WRITE.name().equals(kept)
                || DatasetAccess.WRITE.name().equals(added);
        return read && write ? update : kept;
    }

    /**
     * For each caller node, renumbers seq from 1 in source order (ascending by the call site's
     * line, with an edge whose line is unknown placed after those). The logic that adds edges
     * is split across JCL, CALL, EXEC CICS, and Db2 tables, so left in add order the edges from
     * the same caller would not appear in source order (CICS's XCTL would come after a CALL on
     * a later line). A job's edges keep their add order instead: its steps are added in
     * execution order, and a step expanded from a PROC carries the PROC member's line, which
     * says nothing about its place in the job.
     *
     * <p>The ordering of rows (the set's iteration order) is left unchanged, in add order. That
     * order does not reach persistence: {@link CallGraph} re-sorts the edges by (fromId, toId,
     * kind, resolution) and row IDs follow that sort. Only seq carries the source order.
     */
    private static Set<CallGraphEdge> numberBySourceOrder(Collection<CallGraphEdge> edges) {
        Map<String, List<CallGraphEdge>> byFrom = new LinkedHashMap<>();
        for (CallGraphEdge edge : edges) {
            byFrom.computeIfAbsent(edge.fromId(), k -> new ArrayList<>()).add(edge);
        }
        Map<CallGraphEdge, Integer> seqByEdge = new HashMap<>();
        for (Map.Entry<String, List<CallGraphEdge>> entry : byFrom.entrySet()) {
            List<CallGraphEdge> group = entry.getValue();
            if (!entry.getKey().startsWith(JOB_ID_PREFIX)) {
                group.sort(Comparator.comparing(CallGraphEdge::line,
                        Comparator.nullsLast(Comparator.naturalOrder())));
            }
            int seq = 0;
            for (CallGraphEdge edge : group) {
                seqByEdge.put(edge, ++seq);
            }
        }
        Set<CallGraphEdge> numbered = new LinkedHashSet<>();
        for (CallGraphEdge edge : edges) {
            numbered.add(new CallGraphEdge(edge.fromId(), edge.toId(), edge.kind(),
                    edge.resolution(), seqByEdge.get(edge), edge.line(), edge.attributes()));
        }
        return numbered;
    }
}
