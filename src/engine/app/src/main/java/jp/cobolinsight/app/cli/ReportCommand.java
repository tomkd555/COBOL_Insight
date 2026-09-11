package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * `report` subcommand. Reads the asset inventory and call-relationship summary from a scanned
 * SQLite (--db), reads the lint findings and SQL advice from a prior {@code lint}'s two SARIF
 * files, merges them, and writes a report in both HTML and text formats. Writes the processing
 * summary to standard output as JSON, and the exit code branches on the merged detection results
 * (success=0, warnings=1, errors=2).
 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "資産の走査結果・指摘・呼出関係グラフを統合し、HTML/テキストのレポートを生成する")
public final class ReportCommand implements Callable<Integer> {

    @Option(names = "--db", paramLabel = "FILE", defaultValue = "cobol-insight.db",
            description = "scan 済み SQLite プロジェクトファイル(既定: ${DEFAULT-VALUE})")
    Path databaseFile;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight.sarif",
            description = "lint 済みの指摘一覧ファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--sql-sarif", paramLabel = "FILE", defaultValue = "cobol-insight-sql.sarif",
            description = "lint 済みのSQL助言一覧ファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sqlSarifFile;

    @Option(names = "--html", paramLabel = "FILE", defaultValue = "cobol-insight-report.html",
            description = "HTML レポート出力ファイル(既定: ${DEFAULT-VALUE})")
    Path htmlFile;

    @Option(names = "--text", paramLabel = "FILE", defaultValue = "cobol-insight-report.txt",
            description = "テキストレポート出力ファイル(既定: ${DEFAULT-VALUE})")
    Path textFile;

    @Override
    public Integer call() {
        ReportRunner.Result result = ReportRunner.run(
                new ReportRunner.Options(databaseFile, sarifFile, sqlSarifFile));
        Paths.writeString(htmlFile, result.html());
        Paths.writeString(textFile, result.text());
        System.out.println(result.summaryJson(htmlFile.toString(), textFile.toString()));
        return result.exitCode();
    }
}
