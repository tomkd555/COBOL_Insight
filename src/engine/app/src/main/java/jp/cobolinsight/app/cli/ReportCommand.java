package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `report` subcommand. Reads the asset inventory, call-relationship summary, and scan-derived
 * findings from a scanned SQLite (--db), re-runs the lint detection and SQL findings in memory
 * against the same asset folder, merges them, and writes a report in both HTML and text formats.
 * Writes the processing summary to standard output as JSON, and the exit code branches on the
 * merged detection results (success=0, warnings=1, errors=2).
 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "資産の走査結果・指摘・呼出関係グラフを統合し、HTML/テキストのレポートを生成する")
public final class ReportCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--db", paramLabel = "FILE", defaultValue = "cobol-insight.db",
            description = "scan 済み SQLite プロジェクトファイル(既定: ${DEFAULT-VALUE})")
    Path databaseFile;

    @Option(names = "--html", paramLabel = "FILE", defaultValue = "cobol-insight-report.html",
            description = "HTML レポート出力ファイル(既定: ${DEFAULT-VALUE})")
    Path htmlFile;

    @Option(names = "--text", paramLabel = "FILE", defaultValue = "cobol-insight-report.txt",
            description = "テキストレポート出力ファイル(既定: ${DEFAULT-VALUE})")
    Path textFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Mixin
    RuleOptions ruleOptions;

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        ReportRunner.Result result = ReportRunner.run(new ReportRunner.Options(inputDir,
                databaseFile, searchPaths, codepageOverrides, ruleOptions.reportingRuleSet()));
        Paths.writeString(htmlFile, result.html());
        Paths.writeString(textFile, result.text());
        System.out.println(result.summaryJson(htmlFile.toString(), textFile.toString()));
        return result.exitCode();
    }
}
