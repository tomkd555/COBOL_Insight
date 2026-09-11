package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.linker.LinkResult;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.rules.RuleSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The pipeline each subcommand runs.
 *
 * <p>What a step does is decided in one place: {@code kinds} says which assets the subcommand looks
 * at, and {@code needs} — the union of what its enabled rules declare and what the subcommand
 * requires whatever the rules say — says which analyses are built. {@code scan} always links,
 * because the graph is its output rather than a rule's input.
 */
public final class Pipelines {

    /** Everything the walk of an asset folder can turn up. */
    private static final Set<AssetKind> ALL_KINDS = EnumSet.allOf(AssetKind.class);

    /** What {@code lint} looks at: every kind a bug-detection rule targets. */
    private static final Set<AssetKind> LINTABLE = EnumSet.of(AssetKind.COBOL,
            AssetKind.COPYBOOK, AssetKind.BMS, AssetKind.JCL, AssetKind.SQL);

    private static final Set<AssetKind> COBOL_ONLY = EnumSet.of(AssetKind.COBOL);

    private Pipelines() {
    }

    /** Options for a subcommand whose rules run under {@code command}. */
    public static SourceSet.Options options(Path inputDir, Path databaseFile,
            List<Path> copybookSearchPaths, Map<String, String> codepageOverrides, RuleSet ruleSet,
            Set<Needs> needs) {
        return options(inputDir, databaseFile, copybookSearchPaths, codepageOverrides, ruleSet,
                needs, List.of());
    }

    /** The same, with the directories {@code --proc-path} adds to the member search space. */
    public static SourceSet.Options options(Path inputDir, Path databaseFile,
            List<Path> copybookSearchPaths, Map<String, String> codepageOverrides, RuleSet ruleSet,
            Set<Needs> needs, List<Path> procedureLibraryPaths) {
        return new SourceSet.Options(inputDir, databaseFile, copybookSearchPaths,
                codepageOverrides, ruleSet, needs, procedureLibraryPaths);
    }

    /** {@code scan} with the built-in rules and no configuration file. */
    public static ScanOutcome scan(Path inputDir, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages) {
        return scan(inputDir, databaseFile, copybookPaths, codepages, RuleSet.load((Path) null));
    }

    /** {@code scan}: parse everything, link it, write it to SQLite. */
    public static ScanOutcome scan(Path inputDir, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        return scan(inputDir, databaseFile, copybookPaths, codepages, ruleSet, List.of());
    }

    /** The same, with the PROC and INCLUDE member directories outside the asset folder. */
    public static ScanOutcome scan(Path inputDir, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet, List<Path> procedureLibraryPaths) {
        Set<Needs> needs = EnumSet.of(Needs.SQL, Needs.CALL_GRAPH);
        SourceSet s = new SourceSet(options(inputDir, databaseFile, copybookPaths, codepages,
                ruleSet, needs, procedureLibraryPaths));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(ALL_KINDS, false),
                new Decode(ALL_KINDS),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Link(),
                new Persist()), s);
        return outcomeOf(s);
    }

    /**
     * {@code lint}: bug detection over COBOL, copybooks, BMS and JCL, and SQL advice over the
     * embedded SQL of the COBOL sources, kept apart so each is written to its own SARIF file.
     */
    public static SourceSet lint(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        return lint(inputDir, copybookPaths, codepages, ruleSet, List.of());
    }

    /** The same, with the PROC and INCLUDE member directories outside the asset folder. */
    public static SourceSet lint(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet, List<Path> procedureLibraryPaths) {
        return lint(inputDir, copybookPaths, codepages, ruleSet, procedureLibraryPaths, Scope.ALL);
    }

    /**
     * The same, narrowed to the files {@code --scope} names. The walk stays whole either way, and
     * so does everything but the COBOL: {@link ScopeFilter} decides what is reported.
     */
    public static SourceSet lint(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet, List<Path> procedureLibraryPaths,
            Scope scope) {
        // Needs.SQL is forced: reporting a statement the grammar would not read in full is the
        // pipeline's own obligation, so it must not depend on an SQL rule being enabled.
        Set<Needs> needs = EnumSet.copyOf(needsOf(ruleSet, Command.LINT, Needs.SQL));
        needs.addAll(ruleSet.needs(Command.SQL_LINT));
        SourceSet s = new SourceSet(new SourceSet.Options(inputDir, null, copybookPaths, codepages,
                ruleSet, needs, procedureLibraryPaths, scope));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(LINTABLE, false),
                new Decode(LINTABLE),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Link(),
                new Rules(Command.LINT),
                new Rules(Command.SQL_LINT),
                new ScopeFilter(),
                new Sarif(Command.LINT),
                new Sarif(Command.SQL_LINT)), s);
        return s;
    }

    /**
     * {@code fix}: only the rules that carry a fix. Copybooks come from the search paths as well,
     * because a fix may land in one.
     */
    public static SourceSet fix(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                needsOf(ruleSet, Command.FIX, Needs.SOURCE_TEXT)));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(COBOL_ONLY, true),
                new Decode(COBOL_ONLY),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Rules(Command.FIX)), s);
        return s;
    }

    /** {@code translate}: parse the COBOL and its copybooks; no rule runs. */
    public static SourceSet translate(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages,
                RuleSet.load((Path) null), Set.of()));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(COBOL_ONLY, true),
                new Decode(COBOL_ONLY),
                new Parse()), s);
        return s;
    }

    private static Set<Needs> needsOf(RuleSet ruleSet, Command command, Needs... always) {
        Set<Needs> needs = EnumSet.noneOf(Needs.class);
        needs.addAll(ruleSet.needs(command));
        needs.addAll(List.of(always));
        return needs;
    }

    // ---- scan result assembly ----

    private static ScanOutcome outcomeOf(SourceSet s) {
        Persist.Outcome outcome = s.artifact(Persist.Outcome.class).orElseThrow();
        LinkResult link = s.artifact(LinkResult.class).orElseThrow();

        // findingCount counts what decoding and parsing reported. The linker's findings are its
        // record of how each call resolved, so they steer the exit code without being counted.
        List<Finding> forExitCode = new ArrayList<>(s.findings());
        forExitCode.addAll(link.findings());
        int exitCode = ExitCodes.fromFindings(forExitCode);
        if (outcome.hasError()) {
            exitCode = ExitCodes.ERRORS;
        } else if (outcome.hasWarning() && exitCode == ExitCodes.SUCCESS) {
            exitCode = ExitCodes.WARNINGS;
        }

        ScanOutcome.Summary summary = new ScanOutcome.Summary(outcome.analyzed(),
                outcome.skipped(), outcome.removed(), outcome.findingCount(), exitCode,
                s.discovery().truncated(), s.discovery().undecided(),
                s.discovery().mismatches().stream()
                        .map(m -> new ScanOutcome.KindMismatch(m.relPath(), m.byExtension().name(),
                                m.byContent().name()))
                        .toList(),
                s.unreadableIncludingDiscovery(), diagnosticsOf(s));
        return new ScanOutcome(summary, link.graph(), link.findings(), copyExpansionsOf(s),
                s.discoveryWarnings());
    }

    /** The rule ids of the findings that say a statement was read only in part. */
    private static final Set<String> DIAGNOSTIC_RULE_IDS = Set.of(Finding.JCL_SYNTAX_RULE_ID,
            Finding.JCL_DIRECTIVE_RULE_ID, Finding.SQL_SYNTAX_RULE_ID);

    /**
     * How many places of each file this run read only in part, in relative-path order. Every file of
     * the walk is parsed on every run — only the writing of its rows is skipped when its content has
     * not changed — so the counts cover the whole folder, not just the files that were rewritten.
     */
    private static Map<String, Integer> diagnosticsOf(SourceSet s) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SourceUnit unit : s.units()) {
            int count = (int) s.findingsOf(unit.relPath()).stream()
                    .filter(finding -> DIAGNOSTIC_RULE_IDS.contains(finding.ruleId())).count();
            if (count > 0) {
                counts.put(unit.relPath(), count);
            }
        }
        return counts;
    }

    /**
     * The COPY expansions of every program, in relative-path order. A copybook under the asset
     * folder is named relative to it; one outside keeps its absolute path. Programs with no
     * expansion are left out.
     */
    private static ScanOutcome.CopyExpansions copyExpansionsOf(SourceSet s) {
        List<ScanOutcome.CopyExpansions.ProgramExpansion> programs = new ArrayList<>();
        s.programsByPath().forEach((relPath, model) -> {
            if (model.copyInlineExpansions().isEmpty()) {
                return;
            }
            programs.add(new ScanOutcome.CopyExpansions.ProgramExpansion(relPath,
                    model.programId(), relativized(s, model)));
        });
        programs.sort(Comparator.comparing(
                ScanOutcome.CopyExpansions.ProgramExpansion::relPath));
        return new ScanOutcome.CopyExpansions(programs);
    }

    private static List<CopyInlineExpansion> relativized(SourceSet s, CobolSemanticModel model) {
        return model.copyInlineExpansions().stream()
                .map(expansion -> new CopyInlineExpansion(expansion.copyStatementLine(),
                        expansion.copybookName(),
                        Paths.relativizeOrAbsolute(s.root(), Path.of(expansion.copybookPath())),
                        expansion.lines()))
                .toList();
    }
}
