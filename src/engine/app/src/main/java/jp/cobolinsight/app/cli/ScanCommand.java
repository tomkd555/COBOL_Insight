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
 * `scan`. Scans and analyzes the asset folder, persists the results to a SQLite project file, and
 * writes a processing summary to stdout as JSON. Optionally, with {@code --copy-expansion}, writes
 * the inline expansion of COPY statements as JSON.
 */
@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "資産フォルダを解析し、結果をSQLiteへ永続化する")
public final class ScanCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

    /**
     * No rule runs under {@code scan}, so the file is only read and checked here: a malformed or
     * wrong-version {@code rules.json} fails this stage instead of the next one. The option stays
     * because the GUI passes the same options object to both stages.
     */
    @Mixin
    RuleOptions ruleOptions;

    @Option(names = "--copy-expansion", paramLabel = "FILE",
            description = "COPY 文のインライン展開のJSON出力先")
    Path copyExpansionFile;

    @Override
    public Integer call() {
        // The copybook paths go in as given: with none, the pipeline's own walk decides them, so
        // the folder is walked once.
        ScanOutcome result = Pipelines.scan(options.inputDir, options.databaseFile,
                options.copybookPaths, options.codepageOverrides,
                ruleOptions.reportingRuleSet(), options.procedureLibraryPaths);
        String expansionPath = null;
        if (copyExpansionFile != null) {
            Paths.writeString(copyExpansionFile, result.copyExpansions().toJson());
            expansionPath = copyExpansionFile.toString().replace('\\', '/');
        }
        for (String warning : result.warnings()) {
            System.err.println("警告: " + warning);
        }
        System.out.println(result.summary()
                .toJson(options.databaseFile.toString(), expansionPath));
        return result.summary().exitCode();
    }
}
