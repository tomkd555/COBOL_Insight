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
 * `fix preview` サブコマンド。修正案を原本と修正後の unified diff として算出し、ANSI 着色で標準出力へ
 * 表示する。原本ファイルもソースも変更しない。任意で {@code --html} により外部資産に依存しない
 * 自己完結 HTML へ差分を書き出す。着色は接続先が端末のときのみ行い、リダイレクト時は素の diff を出す。
 * 終了コードは解析段の検出結果で分岐する(復号・パース失敗があれば 2)。
 */
@Command(name = "preview", mixinStandardHelpOptions = true,
        description = "修正案の差分を unified diff で表示する(原本・ソース不変)")
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
                // 原本コピー句は書き換えず提示のみ。影響範囲として組み込み元プログラムを併記する。
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
