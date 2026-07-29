package jp.cobolinsight.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** picocliサブコマンド群のmain入口。 */
@Command(name = "cobol-insight", mixinStandardHelpOptions = true, version = "COBOL Insight 0.1.0-m2",
        description = "COBOL資産の統合解析ツール",
        subcommands = {ScanCommand.class, CallGraphCommand.class, LintCommand.class,
                SqlAdviseCommand.class, ReportCommand.class, TranspileCommand.class,
                FixCommand.class})
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        useUtf8Streams();
        System.exit(new CommandLine(new Main()).execute(args));
    }

    /**
     * 標準出力・標準エラーの文字コードをUTF-8へ固定する。JDK18以降、既定の文字コード
     * ({@code file.encoding})はUTF-8である一方、標準出力の文字コード({@code stdout.encoding})は
     * 実行環境の native encoding のままである。picocli は PrintWriter 経由で前者を使い、
     * サブコマンドは {@code System.out.println} で後者を使うため、固定しないと同じ実行の出力に
     * 2つの文字コードが混在する。GUI はこの標準出力をUTF-8として読む。
     */
    private static void useUtf8Streams() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));
    }
}
