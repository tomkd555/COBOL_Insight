package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
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
 * {@code lint}. Runs the bug-detection rules over the asset folder's COBOL, copybooks and BMS and
 * returns the findings, with decode and parse failures folded in as errors.
 *
 * <p>{@code singleFile} narrows the run to one source and its copybook search paths.
 */
public final class LintRunner {

    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet, Path singleFile) {

        /** The default rule set: every built-in rule, no configuration file. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides) {
            this(inputDir, copybookSearchPaths, codepageOverrides, RuleSet.load((Path) null), null);
        }

        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides, RuleSet ruleSet) {
            this(inputDir, copybookSearchPaths, codepageOverrides, ruleSet, null);
        }
    }

    public record Result(List<Finding> findings, List<String> analyzed, String sarifJson,
            int exitCode) {

        public Result {
            findings = List.copyOf(findings);
            analyzed = List.copyOf(analyzed);
        }

        public long countByLevel(FindingLevel level) {
            return findings.stream().filter(f -> f.level() == level).count();
        }

        public String summaryJson(String sarifFilePath) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.name("analyzed").beginArray();
            for (String path : analyzed) {
                writer.value(path);
            }
            writer.endArray();
            writer.name("findingCount").value(findings.size())
                    .name("errors").value(countByLevel(FindingLevel.ERROR))
                    .name("warnings").value(countByLevel(FindingLevel.WARNING))
                    .name("notes").value(countByLevel(FindingLevel.NOTE))
                    .name("sarifFile").value(sarifFilePath.replace('\\', '/'))
                    .name("exitCode").value(exitCode)
                    .endObject();
            return writer.toString();
        }
    }

    private LintRunner() {
    }

    public static Result run(Options options) {
        SourceSet s = Pipelines.lint(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides(), options.ruleSet(), options.singleFile());
        reportWarnings(s);
        List<Finding> findings = new ArrayList<>(s.findings());
        findings.addAll(s.ruleFindings(Command.LINT));
        findings.sort(SarifWriter.findingOrder());
        return new Result(findings, List.copyOf(s.programsByPath().keySet()),
                s.sarif(Command.LINT), ExitCodes.fromFindings(findings));
    }

    /** What the walk dropped or reinterpreted. Only {@code scan} has a summary JSON to carry it. */
    static void reportWarnings(SourceSet s) {
        for (String warning : s.discoveryWarnings()) {
            System.err.println("警告: " + warning);
        }
    }
}
