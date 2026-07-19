package jp.cobolinsight.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

/**
 * `scan` サブコマンド。資産フォルダ(bms/cobol/copy|copybook/jcl 構成)を走査して解析し、
 * 結果をSQLiteプロジェクトファイルへ永続化して処理サマリをJSONで標準出力へ書く。
 */
@Command(name = "scan", mixinStandardHelpOptions = true,
        description = "資産フォルダを解析し、結果をSQLiteへ永続化する")
public final class ScanCommand implements Callable<Integer> {

    @Mixin
    CommonScanOptions options;

    @Override
    public Integer call() {
        ScanRunner.Summary summary = ScanRunner.run(options.toRunnerOptions());
        System.out.println(summary.toJson());
        return summary.exitCode();
    }
}
