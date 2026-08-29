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
 * `report` サブコマンド。scan 済み SQLite(--db)から資産インベントリ・呼出関係の
 * 要約・scan 由来 finding を読み、同じ資産フォルダに対し lint 検出と SQL 指摘をメモリ上で再実行して
 * 統合し、HTML とテキストの両形式でレポートを書き出す。処理サマリを JSON で標準出力へ書き、
 * 終了コードは統合した検出結果で分岐する(成功=0・警告あり=1・エラー=2)。
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
