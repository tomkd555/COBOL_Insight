package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.fix.ReparseResult;
import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.fix.ReparseVerifier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The `fix apply` subcommand. Leaves the original files unchanged and writes the fixed sources to
 * the output directory (default {@code fix/}), preserving the original relative path layout. Each
 * output file is passed through a reparse verification gate after being written, detecting fixes
 * that cannot be parsed due to column misalignment, token merging, literal corruption, and the
 * like as errors. The exit code branches on both the analysis-stage detection results and the
 * reparse verification results (2 if either has an error).
 *
 * <p>Fixes originating from copybooks are not written out. Since a copybook is expanded into
 * multiple programs, the original is left unmodified; instead its impact (the list of importing
 * programs) is reported alongside it, leaving the decision to the user ({@code copybookFixes}).
 */
@Command(name = "apply", mixinStandardHelpOptions = true,
        description = "修正後原始プログラムを出力先へ書き出し、再パース検証する(原本不変)")
public final class FixApplyCommand implements Callable<Integer> {

    @Mixin
    FixCommonOptions options;

    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--out", paramLabel = "DIR", defaultValue = "fix",
            description = "修正後原始プログラムの出力先(元の相対パス構成を保持。既定: ${DEFAULT-VALUE})")
    Path outputDir;

    /** A fix originating from a copybook. The original is left unmodified; the list of importing programs is reported alongside it. */
    record CopybookFix(String relPath, List<String> importers) {
    }

    /** The write-out and reparse results: the programs written, the copybook fixes left as reported only, and the set of reparse errors. */
    record ApplyOutcome(List<String> written, List<CopybookFix> copybookFixes,
            List<Finding> reparseFindings) {
    }

    @Override
    public Integer call() {
        FixRunner.Result result =
                new FixRunner().run(options.toRunnerOptions(ruleOptions.reportingRuleSet()));
        ApplyOutcome outcome = applyFixes(result.fileFixes(), outputDir,
                EngineWiring.reparseVerifier(),
                options.resolvedCopybookPaths());

        List<Finding> combined = new ArrayList<>(result.analysisFindings());
        combined.addAll(outcome.reparseFindings());
        int exitCode = ExitCodes.fromFindings(combined);
        System.out.println(summaryJson(outcome, result, exitCode));
        return exitCode;
    }

    /**
     * Applies the fixes to the output directory. Main program bodies are written out preserving
     * the relative layout and reparse-verified. Fixes originating from copybooks are not written
     * out; instead they are collected into {@link CopybookFix} together with the list of
     * importing programs.
     */
    static ApplyOutcome applyFixes(List<FixRunner.FileFix> fixes, Path outputDir,
            ReparseVerifier verifier, List<Path> copybookSearchPaths) {
        List<String> written = new ArrayList<>();
        List<CopybookFix> copybookFixes = new ArrayList<>();
        List<Finding> reparseFindings = new ArrayList<>();
        for (FixRunner.FileFix fix : fixes) {
            if (fix.copybook()) {
                copybookFixes.add(new CopybookFix(fix.relPath(), fix.importers()));
                continue;
            }
            write(outputDir.resolve(fix.relPath()), fix.fixedBytes());
            written.add(fix.relPath());
            ReparseResult reparse = verifier.verify(fix.relPath(), fix.fixedBytes(),
                    fix.charsetName(), copybookSearchPaths);
            reparse.errorFinding().ifPresent(reparseFindings::add);
        }
        return new ApplyOutcome(written, copybookFixes, reparseFindings);
    }

    private String summaryJson(ApplyOutcome outcome, FixRunner.Result result, int exitCode) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.name("outputDir").value(outputDir.toString().replace('\\', '/'));
        writer.name("writtenFiles").beginArray();
        for (String file : outcome.written()) {
            writer.value(file);
        }
        writer.endArray();
        writer.name("copybookFixes").beginArray();
        for (CopybookFix copybookFix : outcome.copybookFixes()) {
            writer.beginObject().name("copybook").value(copybookFix.relPath())
                    .name("importers").beginArray();
            for (String importer : copybookFix.importers()) {
                writer.value(importer);
            }
            writer.endArray().endObject();
        }
        writer.endArray();
        writer.name("fixCount").value(result.fixCount())
                .name("analysisErrors").value(result.analysisErrors())
                .name("reparseFailures").value(outcome.reparseFindings().size())
                .name("exitCode").value(exitCode)
                .endObject();
        return writer.toString();
    }

    private static void write(Path target, byte[] bytes) {
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
