package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.json.JsonWriter;
import jp.cobolinsight.core.pipeline.ExitCodes;
import jp.cobolinsight.core.fix.ReparseResult;
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
 * `fix apply` サブコマンド。原本ファイルは変更せず、修正後ソースを出力先(既定 {@code fix/})へ、
 * 元の相対パス構成を保って書き出す。各出力ファイルは書き出し後に再パース検証ゲートを通し、桁崩れ・
 * トークン結合・リテラル破損などでパースできない修正を error として検出する。終了コードは解析段の
 * 検出結果と再パース検証結果で分岐する(いずれかに error があれば 2)。
 *
 * <p>コピー句由来の修正は書き出さない。コピー句は複数プログラムへ展開されるため、原本を書き換えず
 * 影響範囲(取り込むプログラム一覧)を併記して利用者の判断に委ねる({@code copybookFixes})。
 */
@Command(name = "apply", mixinStandardHelpOptions = true,
        description = "修正後ソースを出力先へ書き出し、再パース検証する(原本不変)")
public final class FixApplyCommand implements Callable<Integer> {

    @Mixin
    FixCommonOptions options;

    @Option(names = "--out", paramLabel = "DIR", defaultValue = "fix",
            description = "修正後ソースの出力先(元の相対パス構成を保持。既定: ${DEFAULT-VALUE})")
    Path outputDir;

    /** コピー句由来の修正。原本は書き換えず、取り込むプログラム一覧を併記する。 */
    record CopybookFix(String relPath, List<String> importers) {
    }

    /** 書き出し・再パース結果。書き出したプログラム・提示に留めたコピー句修正・再パース error 群。 */
    record ApplyOutcome(List<String> written, List<CopybookFix> copybookFixes,
            List<Finding> reparseFindings) {
    }

    @Override
    public Integer call() {
        FixRunner.Result result = new FixRunner().run(options.toRunnerOptions());
        ApplyOutcome outcome = applyFixes(result.fileFixes(), outputDir, new ReparseVerifier(),
                options.resolvedCopybookPaths());

        List<Finding> combined = new ArrayList<>(result.analysisFindings());
        combined.addAll(outcome.reparseFindings());
        int exitCode = ExitCodes.fromFindings(combined);
        System.out.println(summaryJson(outcome, result, exitCode));
        return exitCode;
    }

    /**
     * 修正群を出力先へ適用する。プログラム本体は相対構成を保って書き出し再パース検証する。コピー句
     * 由来の修正は書き出さず、取り込むプログラム一覧を併記して {@link CopybookFix} へ集約する。
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
