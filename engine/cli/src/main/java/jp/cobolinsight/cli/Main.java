package jp.cobolinsight.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** picocliサブコマンド群のmain入口。 */
@Command(name = "cobol-insight", mixinStandardHelpOptions = true, version = "COBOL Insight 0.1.0-m2",
        description = "COBOL資産の統合解析ツール",
        subcommands = {ScanCommand.class, CallGraphCommand.class, LintCommand.class})
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
