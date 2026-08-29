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

/**
 * The pipeline each subcommand runs.
 *
 * <p>What a step does is decided in one place: {@code kinds} says which assets the subcommand looks
 * at, and {@code needs} — the union of what its enabled rules declare and what the subcommand
 * requires whatever the rules say — says which analyses are built. {@code scan} and
 * {@code call-graph} always link, because the graph is their output rather than a rule's input.
 */
public final class Pipelines {

    /** Everything the walk of an asset folder can turn up. */
    private static final Set<AssetKind> ALL_KINDS = EnumSet.allOf(AssetKind.class);

    /** What {@code lint} and {@code report} look at. JCL has no bug-detection rule that reads it. */
    private static final Set<AssetKind> LINTABLE =
            EnumSet.of(AssetKind.COBOL, AssetKind.COPYBOOK, AssetKind.BMS);

    private static final Set<AssetKind> COBOL_ONLY = EnumSet.of(AssetKind.COBOL);

    private Pipelines() {
    }

    /** Options for a subcommand whose rules run under {@code command}. */
    public static SourceSet.Options options(Path inputDir, Path databaseFile,
            List<Path> copybookSearchPaths, Map<String, String> codepageOverrides, RuleSet ruleSet,
            Set<Needs> needs, Path singleFile) {
        return new SourceSet.Options(inputDir, databaseFile, copybookSearchPaths,
                codepageOverrides, ruleSet, needs, singleFile);
    }

    /** {@code scan} with the built-in rules and no configuration file. */
    public static ScanOutcome scan(Path inputDir, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages) {
        return scan(inputDir, databaseFile, copybookPaths, codepages, RuleSet.load((Path) null));
    }

    /** {@code scan} and {@code call-graph}: parse everything, link it, write it to SQLite. */
    public static ScanOutcome scan(Path inputDir, Path databaseFile, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        Set<Needs> needs = needsOf(ruleSet, Command.SCAN,
                Needs.SEMANTIC, Needs.SQL, Needs.BMS, Needs.CALL_GRAPH);
        SourceSet s = new SourceSet(options(inputDir, databaseFile, copybookPaths, codepages,
                ruleSet, needs, null));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(ALL_KINDS, false),
                new Decode(ALL_KINDS),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Link(),
                new Rules(Command.SCAN),
                new Persist()), s);
        return outcomeOf(s);
    }

    /** {@code lint}: bug detection over COBOL, copybooks and BMS. */
    public static SourceSet lint(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet, Path singleFile) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                needsOf(ruleSet, Command.LINT, Needs.SEMANTIC), singleFile));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(LINTABLE, singleFile != null),
                new Decode(LINTABLE),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Link(),
                new Rules(Command.LINT),
                new Sarif(Command.LINT)), s);
        return s;
    }

    /** {@code sql-lint}: advice on the embedded SQL of the COBOL sources. */
    public static SourceSet sqlLint(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                needsOf(ruleSet, Command.SQL_LINT, Needs.SEMANTIC, Needs.SQL), null));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(COBOL_ONLY, false),
                new Decode(COBOL_ONLY),
                new Parse(),
                new Semantic(),
                new Rules(Command.SQL_LINT),
                new Sarif(Command.SQL_LINT)), s);
        return s;
    }

    /**
     * {@code report}: bug detection and SQL advice over one parse of the assets, kept apart so the
     * report can show them in their own sections.
     */
    public static SourceSet report(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        Set<Needs> needs = EnumSet.copyOf(needsOf(ruleSet, Command.REPORT, Needs.SEMANTIC,
                Needs.SQL));
        needs.addAll(ruleSet.needs(Command.SQL_LINT));
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                needs, null));
        Pipeline.run(List.of(
                new Discover(),
                new Classify(LINTABLE, false),
                new Decode(LINTABLE),
                new Parse(),
                new Semantic(),
                new Cfg(),
                new DataFlow(),
                new Link(),
                new Rules(Command.REPORT),
                new Rules(Command.SQL_LINT),
                new Sarif(Command.REPORT),
                new Sarif(Command.SQL_LINT)), s);
        return s;
    }

    /**
     * {@code fix preview} and {@code fix apply}: only the rules that carry a fix. Copybooks come
     * from the search paths as well, because a fix may land in one.
     */
    public static SourceSet fix(Path inputDir, List<Path> copybookPaths,
            Map<String, String> codepages, RuleSet ruleSet) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                needsOf(ruleSet, Command.FIX, Needs.SEMANTIC, Needs.SOURCE_TEXT), null));
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
            Map<String, String> codepages, RuleSet ruleSet) {
        SourceSet s = new SourceSet(options(inputDir, null, copybookPaths, codepages, ruleSet,
                Set.of(Needs.SEMANTIC), null));
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
        forExitCode.addAll(s.ruleFindings(Command.SCAN));
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
                s.unreadableIncludingDiscovery());
        return new ScanOutcome(summary, link.graph(), link.findings(), copyExpansionsOf(s));
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
