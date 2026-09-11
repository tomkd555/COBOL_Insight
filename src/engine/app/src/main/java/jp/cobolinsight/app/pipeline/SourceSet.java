package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.sql.SqlRoutineDefinition;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.rules.RuleSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything one run of the pipeline knows. Steps read what earlier steps put here and add their
 * own results; nothing is passed between steps any other way.
 *
 * <p>Per-asset results are kept keyed by relative path so a later step can tell which file a model
 * came from — persistence needs that, and so does the report of what could not be analysed.
 */
public final class SourceSet {

    /**
     * What the run was asked to do. {@code needs} is the union of what the enabled rules require
     * and what the subcommand requires regardless of rules, and it decides which steps do work.
     */
    public record Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet, Set<Needs> needs,
            List<Path> procedureLibraryPaths, Scope scope) {

        public Options {
            copybookSearchPaths = List.copyOf(copybookSearchPaths);
            codepageOverrides = Map.copyOf(codepageOverrides);
            needs = Set.copyOf(needs);
            procedureLibraryPaths = List.copyOf(procedureLibraryPaths);
        }

        /** A run over the whole asset folder, which is every subcommand but a scoped {@code lint}. */
        public Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, RuleSet ruleSet, Set<Needs> needs,
                List<Path> procedureLibraryPaths) {
            this(inputDir, databaseFile, copybookSearchPaths, codepageOverrides, ruleSet, needs,
                    procedureLibraryPaths, Scope.ALL);
        }

        /** A run with no PROC library of its own: the walk is the whole search space. */
        public Options(Path inputDir, Path databaseFile, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, RuleSet ruleSet, Set<Needs> needs) {
            this(inputDir, databaseFile, copybookSearchPaths, codepageOverrides, ruleSet, needs,
                    List.of());
        }

        public boolean requires(Needs need) {
            return needs.contains(need);
        }
    }

    private final Options options;

    private SourceDiscovery.Result discovery =
            new SourceDiscovery.Result(List.of(), List.of(), false, List.of(), List.of(),
                    List.of());
    /** Files that discovery accepted but that could not be read afterwards. */
    private final List<String> unreadable = new ArrayList<>();
    /** Held once decided, because {@link Parse} asks for it per file. */
    private List<Path> copybookSearchPaths;
    private final List<SourceUnit> units = new ArrayList<>();
    private final Map<String, byte[]> bytes = new LinkedHashMap<>();
    private final Map<String, DecodedSource> decoded = new LinkedHashMap<>();
    private final Map<String, CobolSemanticModel> programsByPath = new TreeMap<>();
    private final Map<String, List<JclJobModel>> jobsByPath = new TreeMap<>();
    private final Map<String, List<BmsMapset>> mapsetsByPath = new TreeMap<>();
    private final Map<String, List<SqlStatementModel>> sqlByPath = new TreeMap<>();
    /** The routines the SQL scripts of the walk define, in the order the scripts write them. */
    private final List<SqlRoutineDefinition> sqlRoutines = new ArrayList<>();
    /** Assets whose bytes could not be decoded or parsed, and why. Isolated nodes in the graph. */
    private final Map<String, String> unanalyzable = new TreeMap<>();
    private ControlFlowGraphs cfgs = new ControlFlowGraphs(List.of());
    private DataFlowFacts flows = new DataFlowFacts(List.of());
    private CallGraph callGraph;
    private final List<Finding> findings = new ArrayList<>();
    private final Map<String, List<Finding>> findingsByPath = new LinkedHashMap<>();
    private final Map<Command, List<Finding>> ruleFindings = new LinkedHashMap<>();
    private final Map<Command, String> sarif = new LinkedHashMap<>();
    private final Map<Class<?>, Object> artifacts = new LinkedHashMap<>();

    public SourceSet(Options options) {
        this.options = options;
    }

    public Options options() {
        return options;
    }

    public Path root() {
        return options.inputDir();
    }

    public boolean requires(Needs need) {
        return options.requires(need);
    }

    public List<jp.cobolinsight.core.rule.Rule> rulesFor(Command command) {
        return options.ruleSet().forCommand(command);
    }

    public SourceDiscovery.Result discovery() {
        return discovery;
    }

    public void discovery(SourceDiscovery.Result report) {
        this.discovery = report;
    }

    /**
     * Where COPY looks. {@code --copybook-path} wins; with none given, wherever this run's own walk
     * found copybooks becomes the search path — the whole walk, so a copybook left out of a scope
     * still resolves. Deciding by folder name instead would leave a COPY unresolved as soon as
     * someone put the copybook somewhere else.
     */
    public List<Path> copybookSearchPaths() {
        if (copybookSearchPaths == null) {
            copybookSearchPaths = options.copybookSearchPaths().isEmpty()
                    ? discovery.copybookDirectories() : options.copybookSearchPaths();
        }
        return copybookSearchPaths;
    }

    public List<String> unreadable() {
        return unreadable;
    }

    /** Everything that could not be read: what the walk hit, plus what failed after it. */
    public List<String> unreadableIncludingDiscovery() {
        List<String> all = new ArrayList<>(discovery.unreadable());
        all.addAll(unreadable);
        return all;
    }

    /** Everything worth telling the user about the walk, discovery's own list plus late failures. */
    public List<String> discoveryWarnings() {
        List<String> messages = new ArrayList<>(discovery.warnings());
        if (!unreadable.isEmpty()) {
            messages.add(unreadable.size() + "件は読み取れなかったため対象から外しました: "
                    + String.join(", ", unreadable));
        }
        return messages;
    }

    public List<SourceUnit> units() {
        return units;
    }

    public List<SourceUnit> unitsOf(AssetKind kind) {
        return units.stream().filter(unit -> unit.kind() == kind).toList();
    }

    /**
     * What the asset folder calls each file, by absolute path. The whole walk answers, not just the
     * units: a scope narrows what is analysed, never what a finding may be named after. The units
     * are added on top of it for the copybooks {@code fix} and {@code translate} reach through a
     * search path, which the walk of the asset folder never saw.
     */
    public Map<Path, String> relPathByAbsPath() {
        Map<Path, String> byAbsPath = new LinkedHashMap<>();
        for (SourceDiscovery.DiscoveredFile file : discovery.files()) {
            byAbsPath.put(file.absPath().toAbsolutePath().normalize(), file.relPath());
        }
        for (SourceUnit unit : units) {
            byAbsPath.putIfAbsent(unit.absPath().toAbsolutePath().normalize(), unit.relPath());
        }
        return byAbsPath;
    }

    public Map<String, byte[]> bytes() {
        return bytes;
    }

    public Map<String, DecodedSource> decoded() {
        return decoded;
    }

    public Map<String, CobolSemanticModel> programsByPath() {
        return programsByPath;
    }

    /** Parsed COBOL programs in relative-path order. */
    public List<CobolSemanticModel> programs() {
        return List.copyOf(programsByPath.values());
    }

    /** The jobs of each JCL file, in the order their JOB cards stand in it. */
    public Map<String, List<JclJobModel>> jobsByPath() {
        return jobsByPath;
    }

    public List<JclJobModel> jobs() {
        List<JclJobModel> all = new ArrayList<>();
        jobsByPath.values().forEach(all::addAll);
        return all;
    }

    public Map<String, List<BmsMapset>> mapsetsByPath() {
        return mapsetsByPath;
    }

    public List<BmsMapset> mapsets() {
        List<BmsMapset> all = new ArrayList<>();
        mapsetsByPath.values().forEach(all::addAll);
        return all;
    }

    public Map<String, List<SqlStatementModel>> sqlByPath() {
        return sqlByPath;
    }

    /** SQL statements grouped by PROGRAM-ID, which is how the linker asks for them. */
    public Map<String, List<SqlStatementModel>> sqlByProgramId() {
        Map<String, List<SqlStatementModel>> byProgramId = new TreeMap<>();
        programsByPath.forEach((relPath, model) -> {
            List<SqlStatementModel> statements = sqlByPath.get(relPath);
            if (statements != null && !statements.isEmpty()) {
                byProgramId.computeIfAbsent(model.programId(), key -> new ArrayList<>())
                        .addAll(statements);
            }
        });
        return byProgramId;
    }

    public List<SqlStatementModel> sql() {
        List<SqlStatementModel> all = new ArrayList<>();
        sqlByPath.values().forEach(all::addAll);
        return all;
    }

    public List<SqlRoutineDefinition> sqlRoutines() {
        return sqlRoutines;
    }

    /** The statements of the SQL scripts alone, keyed by path: what no PROGRAM-ID owns. */
    public Map<String, List<SqlStatementModel>> sqlByScript() {
        Map<String, List<SqlStatementModel>> byScript = new TreeMap<>();
        for (SourceUnit unit : unitsOf(AssetKind.SQL)) {
            List<SqlStatementModel> statements = sqlByPath.get(unit.relPath());
            if (statements != null && !statements.isEmpty()) {
                byScript.put(unit.relPath(), statements);
            }
        }
        return byScript;
    }

    public Map<String, String> unanalyzable() {
        return unanalyzable;
    }

    public ControlFlowGraphs cfgs() {
        return cfgs;
    }

    public void cfgs(ControlFlowGraphs value) {
        this.cfgs = value;
    }

    public DataFlowFacts flows() {
        return flows;
    }

    public void flows(DataFlowFacts value) {
        this.flows = value;
    }

    public Optional<CallGraph> callGraph() {
        return Optional.ofNullable(callGraph);
    }

    public void callGraph(CallGraph value) {
        this.callGraph = value;
    }

    /** Decode and parse failures. These are the pipeline's own findings, not a rule's. */
    public List<Finding> findings() {
        return findings;
    }

    /**
     * Records a pipeline finding against the source it came from. Persistence needs the source, and
     * a finding's own location cannot always supply it: a parse failure is located by the absolute
     * path the frontend was handed, not by the path the asset folder knows it under.
     */
    public void addFinding(String relPath, Finding finding) {
        findings.add(finding);
        findingsByPath.computeIfAbsent(relPath, key -> new ArrayList<>()).add(finding);
    }

    public List<Finding> findingsOf(String relPath) {
        return findingsByPath.getOrDefault(relPath, List.of());
    }

    /** Findings produced by the rules of one command. A run may evaluate more than one set. */
    public List<Finding> ruleFindings(Command command) {
        return ruleFindings.computeIfAbsent(command, key -> new ArrayList<>());
    }

    /** The SARIF document for one command's rules, once {@link Sarif} has run. */
    public String sarif(Command command) {
        return sarif.getOrDefault(command, "");
    }

    public void sarif(Command command, String json) {
        sarif.put(command, json);
    }

    public <T> Optional<T> artifact(Class<T> type) {
        return Optional.ofNullable(artifacts.get(type)).map(type::cast);
    }

    public <T> void artifact(Class<T> type, T value) {
        artifacts.put(type, value);
    }

    /** The artifacts a rule may ask for, with the graphs and facts this run actually built. */
    Map<Class<?>, Object> artifactsWith(ControlFlowGraphs graphs, DataFlowFacts facts) {
        Map<Class<?>, Object> all = new LinkedHashMap<>(artifacts);
        all.put(ControlFlowGraphs.class, graphs);
        all.put(DataFlowFacts.class, facts);
        return all;
    }
}
