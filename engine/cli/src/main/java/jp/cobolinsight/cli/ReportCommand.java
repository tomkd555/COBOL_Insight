package jp.cobolinsight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * `report` サブコマンド(FR-07・裁定A4)。scan 済み SQLite(--db)から資産インベントリ・呼出関係の
 * 要約・scan 由来 finding を読み、同じ資産フォルダに対し lint 検出と SQL 助言をメモリ上で再実行して
 * 統合し、HTML とテキストの両形式でレポートを書き出す。処理サマリを JSON で標準出力へ書き、
 * 終了コードは統合した検出結果で分岐する(成功=0・警告あり=1・エラー=2)。
 */
@Command(name = "report", mixinStandardHelpOptions = true,
        description = "scan/lint/sql-advise/callgraph の結果を統合し、HTML/テキストのレポートを生成する")
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
            description = "コピー句探索パス(既定: INPUT_DIR配下のcopybook・copy)")
    List<Path> copybookPaths = new ArrayList<>();

    @Option(names = "--codepage", paramLabel = "FILE=CHARSET",
            description = "ファイル単位のコードページ手動指定(相対パスまたはファイル名=コードページ)。自動判別に優先する")
    Map<String, String> codepageOverrides = new LinkedHashMap<>();

    @Option(names = "--disable-rule", paramLabel = "RULE_ID",
            description = "無効化するルールID(繰り返し指定可)")
    List<String> disabledRules = new ArrayList<>();

    @Override
    public Integer call() {
        List<Path> searchPaths = CommonScanOptions.resolveCopybookPaths(inputDir, copybookPaths);
        Set<String> disabled = new LinkedHashSet<>(disabledRules);
        ReportRunner.Result result = ReportRunner.run(new ReportRunner.Options(inputDir,
                databaseFile, searchPaths, codepageOverrides, disabled));
        try {
            Files.writeString(htmlFile, result.html(), StandardCharsets.UTF_8);
            Files.writeString(textFile, result.text(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(result.summaryJson(htmlFile.toString(), textFile.toString()));
        return result.exitCode();
    }
}
