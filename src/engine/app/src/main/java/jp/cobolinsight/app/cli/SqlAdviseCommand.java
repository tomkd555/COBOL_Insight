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
 * The `sql-lint` subcommand. Analyzes embedded SQL in an asset folder, writes findings
 * (S001, S002, S004) to a SARIF 2.1.0 file, and writes a processing summary as JSON to stdout. The
 * exit code branches on the detection result (success=0, warnings=1, error=2). Bug detection is
 * handled by the lint subcommand.
 */
@Command(name = "sql-lint", mixinStandardHelpOptions = true,
        description = "資産フォルダの埋め込みSQLを解析し、指摘をSARIFへ出力する")
public final class SqlAdviseCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight-sql.sarif",
            description = "指摘の一覧を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

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
        SqlAdviseRunner.Result result = SqlAdviseRunner.run(new SqlAdviseRunner.Options(inputDir,
                searchPaths, codepageOverrides, ruleOptions.reportingRuleSet()));
        Paths.writeString(sarifFile, result.sarifJson());
        System.out.println(result.summaryJson(sarifFile.toString()));
        return result.exitCode();
    }
}
