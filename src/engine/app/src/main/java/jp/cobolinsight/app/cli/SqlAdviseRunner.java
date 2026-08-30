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
 * {@code sql-lint}. Parses the COBOL of the asset folder, turns its {@code EXEC SQL} blocks into
 * SQL statement models and runs the SQL advice rules over them. Decode and COBOL parse failures
 * join the findings as errors.
 */
public final class SqlAdviseRunner {

    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet) {

        /** The default rule set: every built-in rule, no configuration file. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides) {
            this(inputDir, copybookSearchPaths, codepageOverrides, RuleSet.load((Path) null));
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

    private SqlAdviseRunner() {
    }

    public static Result run(Options options) {
        SourceSet s = Pipelines.sqlLint(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides(), options.ruleSet());
        LintRunner.reportWarnings(s);
        List<Finding> findings = new ArrayList<>(s.findings());
        findings.addAll(s.ruleFindings(Command.SQL_LINT));
        findings.sort(SarifWriter.findingOrder());
        return new Result(findings, List.copyOf(s.programsByPath().keySet()),
                s.sarif(Command.SQL_LINT), ExitCodes.fromFindings(findings));
    }
}
