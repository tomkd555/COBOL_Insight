package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.Scope;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.rules.RuleSet;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code lint}. Runs the bug-detection rules and the SQL advice rules over one parse of the asset
 * folder's COBOL, copybooks, BMS and JCL, with decode and parse failures folded into the
 * bug-detection findings as errors.
 */
public final class LintRunner {

    /** {@code scope} holds the {@code --scope} paths as the user wrote them, empty for the whole folder. */
    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet,
            List<Path> procedureLibraryPaths, List<String> scope) {

        public Options {
            scope = List.copyOf(scope);
        }

        /** The default rule set: every built-in rule, no configuration file. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides) {
            this(inputDir, copybookSearchPaths, codepageOverrides, RuleSet.load((Path) null));
        }

        /** A run whose PROC and INCLUDE members all sit in the asset folder. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, RuleSet ruleSet) {
            this(inputDir, copybookSearchPaths, codepageOverrides, ruleSet, List.of());
        }

        /** A run over the whole asset folder. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, RuleSet ruleSet,
                List<Path> procedureLibraryPaths) {
            this(inputDir, copybookSearchPaths, codepageOverrides, ruleSet, procedureLibraryPaths,
                    List.of());
        }
    }

    /**
     * What one run produced. {@code scope} holds the scopes the run actually narrowed to — a
     * {@code --scope} that named the asset folder itself is not one of them, because that run
     * covered everything.
     */
    public record Result(List<Finding> findings, List<Finding> sqlFindings, List<String> analyzed,
            String sarifJson, String sqlSarifJson, int exitCode, List<String> scope) {

        public Result {
            findings = List.copyOf(findings);
            sqlFindings = List.copyOf(sqlFindings);
            analyzed = List.copyOf(analyzed);
            scope = List.copyOf(scope);
        }

        public long countByLevel(FindingLevel level) {
            return findings.stream().filter(f -> f.level() == level).count();
        }

        public String summaryJson(String sarifFilePath, String sqlSarifFilePath) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.name("analyzed").beginArray();
            for (String path : analyzed) {
                writer.value(path);
            }
            writer.endArray();
            // Absent for a whole-folder run, so the GUI can tell "everything" from "these files".
            if (!scope.isEmpty()) {
                writer.name("scope").beginArray();
                for (String path : scope) {
                    writer.value(path);
                }
                writer.endArray();
            }
            writer.name("findingCount").value(findings.size())
                    .name("errors").value(countByLevel(FindingLevel.ERROR))
                    .name("warnings").value(countByLevel(FindingLevel.WARNING))
                    .name("notes").value(countByLevel(FindingLevel.NOTE))
                    .name("sarifFile").value(sarifFilePath.replace('\\', '/'))
                    .name("sqlSarifFile").value(sqlSarifFilePath.replace('\\', '/'))
                    .name("sqlFindingCount").value(sqlFindings.size())
                    .name("exitCode").value(exitCode)
                    .endObject();
            return writer.toString();
        }
    }

    private LintRunner() {
    }

    public static Result run(Options options) {
        Scope scope = new Scope(options.scope());
        SourceSet s = Pipelines.lint(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides(), options.ruleSet(), options.procedureLibraryPaths(),
                scope);
        reportWarnings(s);
        reportScopeWithoutCobol(s, scope);
        List<Finding> findings = new ArrayList<>(s.findings());
        findings.addAll(s.ruleFindings(Command.LINT));
        findings.sort(SarifWriter.findingOrder());
        List<Finding> sqlFindings = List.copyOf(s.ruleFindings(Command.SQL_LINT));

        List<Finding> forExitCode = new ArrayList<>(findings);
        forExitCode.addAll(sqlFindings);
        return new Result(findings, sqlFindings, List.copyOf(s.programsByPath().keySet()),
                s.sarif(Command.LINT), s.sarif(Command.SQL_LINT),
                ExitCodes.fromFindings(forExitCode), scope.written());
    }

    /**
     * A scope with no COBOL program in it leaves the rules that read a program and a job together
     * — the DD a program opens against the DD the step allocates, above all — with nothing to
     * compare, so those checks pass in silence. The user chose the scope, so the line says which
     * kind of check did not run rather than refusing the run.
     */
    private static void reportScopeWithoutCobol(SourceSet s, Scope scope) {
        if (scope.isEmpty() || !s.programsByPath().isEmpty()) {
            return;
        }
        System.err.println("警告: 対象範囲に COBOL プログラムがないため、"
                + "プログラムとの照合を伴う検査は行いません。");
    }

    /** What the walk dropped or reinterpreted. {@code scan} prints the same list of its own. */
    static void reportWarnings(SourceSet s) {
        for (String warning : s.discoveryWarnings()) {
            System.err.println("警告: " + warning);
        }
    }
}
