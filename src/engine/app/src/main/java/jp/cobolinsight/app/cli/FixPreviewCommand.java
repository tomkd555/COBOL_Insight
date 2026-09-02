package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.app.fix.UnifiedDiffFormatter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * `fix preview` subcommand. Computes the fix proposal as a unified diff between the original and
 * fixed text, and displays it to standard output with ANSI coloring. Changes neither the original
 * file nor the source. Optionally, {@code --html} writes the diff out as self-contained HTML with no
 * dependency on external assets. Coloring is applied only when the destination is a terminal; a plain
 * diff is emitted when redirected. The exit code branches on the analysis stage's detection results
 * (2 if decoding or parsing failed).
 */
@Command(name = "preview", mixinStandardHelpOptions = true,
        description = "修正案の差分を unified diff で表示する(原本・原始プログラム不変)")
public final class FixPreviewCommand implements Callable<Integer> {

    @Mixin
    FixCommonOptions options;

    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--html", paramLabel = "FILE",
            description = "diff を自己完結HTMLで書き出す")
    Path htmlFile;

    @Override
    public Integer call() {
        FixRunner.Result result =
                new FixRunner().run(options.toRunnerOptions(ruleOptions.reportingRuleSet()));
        UnifiedDiffFormatter formatter = new UnifiedDiffFormatter();
        boolean color = System.console() != null;

        List<DiffRendering.FileDiff> diffs = new ArrayList<>();
        List<String> fixedFiles = new ArrayList<>();
        for (FixRunner.FileFix fix : result.fileFixes()) {
            List<String> diff =
                    formatter.unifiedDiff(fix.relPath(), fix.originalText(), fix.fixedText());
            diffs.add(new DiffRendering.FileDiff(fix.relPath(), diff));
            fixedFiles.add(fix.relPath());
            for (String line : color ? DiffRendering.ansi(diff) : diff) {
                System.out.println(line);
            }
            if (fix.copybook()) {
                // The original copybook is not rewritten, only shown as a proposal. The importing
                // programs are listed alongside it as the affected scope.
                System.out.println("# コピー句 " + fix.relPath()
                        + " は原本を書き換えず提示のみ。組み込み元プログラム: "
                        + String.join(", ", fix.importers()));
            }
        }
        if (htmlFile != null) {
            writeString(htmlFile, DiffRendering.html(diffs));
        }
        System.out.println(summaryJson(fixedFiles, result));
        return ExitCodes.fromFindings(result.analysisFindings());
    }

    private String summaryJson(List<String> fixedFiles, FixRunner.Result result) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.name("fixedFiles").beginArray();
        for (String file : fixedFiles) {
            writer.value(file);
        }
        writer.endArray();
        writer.name("copybookFixes").beginArray();
        for (FixRunner.FileFix fix : result.fileFixes()) {
            if (!fix.copybook()) {
                continue;
            }
            writer.beginObject().name("copybook").value(fix.relPath())
                    .name("importers").beginArray();
            for (String importer : fix.importers()) {
                writer.value(importer);
            }
            writer.endArray().endObject();
        }
        writer.endArray();
        writer.name("fixCount").value(result.fixCount())
                .name("analysisErrors").value(result.analysisErrors());
        if (htmlFile != null) {
            writer.name("htmlFile").value(htmlFile.toString().replace('\\', '/'));
        }
        writer.endObject();
        return writer.toString();
    }

    private static void writeString(Path file, String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
