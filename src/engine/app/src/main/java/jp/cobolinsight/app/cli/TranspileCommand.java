package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.transpile.TargetLanguage;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * The `translate` subcommand. Translates the COBOL in the asset folder verbatim into Python/Java,
 * writes the generated files to the output directory, and saves the COBOL-to-generated-line
 * mapping table to SQLite's LINE_MAP. The exit code branches on decode/parse failure
 * (success = 0, error = 2).
 */
@Command(name = "translate", mixinStandardHelpOptions = true,
        description = "資産フォルダの COBOL を Python/Java へ逐語対訳し、行対応表を保存する")
public final class TranspileCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--language", paramLabel = "LANG", defaultValue = "both",
            description = "対象言語 python|java|both(既定: ${DEFAULT-VALUE})")
    String language;

    @Option(names = "--out", paramLabel = "DIR", defaultValue = "transpile",
            description = "生成ファイルの出力先(既定: ${DEFAULT-VALUE})")
    Path outputDir;

    @Option(names = "--db", paramLabel = "FILE", defaultValue = "cobol-insight.db",
            description = "SQLiteプロジェクトファイル(既定: ${DEFAULT-VALUE})")
    Path databaseFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        List<TargetLanguage> languages = parseLanguages(language);
        TranspileRunner.Result result = TranspileRunner.run(new TranspileRunner.Options(
                inputDir, databaseFile, searchPaths, codepageOverrides, languages, outputDir));
        System.out.println(result.summaryJson(outputDir.toString()));
        return result.exitCode();
    }

    private List<TargetLanguage> parseLanguages(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "python" -> List.of(TargetLanguage.PYTHON);
            case "java" -> List.of(TargetLanguage.JAVA);
            case "both" -> List.of(TargetLanguage.PYTHON, TargetLanguage.JAVA);
            default -> throw new CommandLine.ParameterException(spec.commandLine(),
                    "--language には python|java|both のいずれかを指定してください: " + value);
        };
    }
}
