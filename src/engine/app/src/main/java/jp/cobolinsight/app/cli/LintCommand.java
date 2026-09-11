package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Paths;
import jp.cobolinsight.app.pipeline.Scope;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * `lint`. Statically analyzes the asset folder with the bug-detection rules and the SQL advice
 * rules over one parse, writes each out as its own SARIF 2.1.0 file, and writes the processing
 * summary to standard output as JSON. The exit code branches on the union of both detection results
 * (success=0, warnings=1, errors=2).
 */
@Command(name = "lint", mixinStandardHelpOptions = true,
        description = "資産フォルダをルールで静的解析し、SARIFを出力する")
public final class LintCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "INPUT_DIR", description = "資産フォルダ")
    Path inputDir;

    @Option(names = "--sarif", paramLabel = "FILE", defaultValue = "cobol-insight.sarif",
            description = "指摘の一覧を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sarifFile;

    @Option(names = "--sql-sarif", paramLabel = "FILE", defaultValue = "cobol-insight-sql.sarif",
            description = "埋め込みSQLの助言を書き出すファイル(SARIF 2.1.0形式)(既定: ${DEFAULT-VALUE})")
    Path sqlSarifFile;

    @Option(names = "--copybook-path", paramLabel = "DIR",
            description = "コピー句探索パス(既定: 走査で見つかったコピー句の置き場所)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--proc-path", paramLabel = "DIR",
            description = "PROC・INCLUDEメンバーの探索パス(既定: 資産フォルダ内)")
    List<Path> procedureLibraryPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Option(names = "--scope", paramLabel = "REL_PATH",
            description = "解析する範囲(資産フォルダからの相対パス、ファイルまたはフォルダ)。"
                    + "繰り返し指定できます(既定: 資産フォルダ全体)。"
                    + "範囲外のプログラムは読まないため、"
                    + "解析対象にないプログラムの呼び出し(R048)は報告しません")
    List<String> scopes = new ArrayList<>();

    @Mixin
    RuleOptions ruleOptions;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        LintRunner.Result result;
        try {
            result = LintRunner.run(new LintRunner.Options(inputDir, copybookPaths,
                    codepageOverrides, ruleOptions.reportingRuleSet(), procedureLibraryPaths,
                    scopes));
        } catch (Scope.NotFound e) {
            // Naming a range that is not there is a usage error: the run would analyse less than
            // the author asked for, and say nothing about it.
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--scope: " + e.getMessage());
        }
        Paths.writeString(sarifFile, result.sarifJson());
        Paths.writeString(sqlSarifFile, result.sqlSarifJson());
        System.out.println(result.summaryJson(sarifFile.toString(), sqlSarifFile.toString()));
        return result.exitCode();
    }
}
