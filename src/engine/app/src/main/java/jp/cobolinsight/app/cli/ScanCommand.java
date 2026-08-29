package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `scan`。資産フォルダを走査して解析し、結果をSQLiteプロジェクトファイルへ永続化して処理サマリを
 * JSONで標準出力へ書く。任意で {@code --copy-expansion} により、COPY 文のインライン展開を
 * JSONで書き出す。
 */
@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "資産フォルダを解析し、結果をSQLiteへ永続化する")
public final class ScanCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--copy-expansion", paramLabel = "FILE",
            description = "COPY 文のインライン展開のJSON出力先")
    Path copyExpansionFile;

    @Override
    public Integer call() {
        ScanOutcome result = Pipelines.scan(options.inputDir, options.databaseFile,
                options.resolvedCopybookPaths(), options.codepageOverrides,
                ruleOptions.reportingRuleSet());
        String expansionPath = null;
        if (copyExpansionFile != null) {
            Paths.writeString(copyExpansionFile, result.copyExpansions().toJson());
            expansionPath = copyExpansionFile.toString().replace('\\', '/');
        }
        System.out.println(result.summary()
                .toJson(options.databaseFile.toString(), expansionPath));
        return result.summary().exitCode();
    }
}
