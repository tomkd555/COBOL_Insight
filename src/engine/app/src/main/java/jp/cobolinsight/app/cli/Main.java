package jp.cobolinsight.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** The main entry point for the group of picocli subcommands. */
@Command(name = "cobol-insight", mixinStandardHelpOptions = true, version = "COBOL Insight 0.1.0-m2",
        description = "COBOL資産の統合解析ツール",
        subcommands = {ScanCommand.class, CallGraphCommand.class, LintCommand.class,
                SqlAdviseCommand.class, ReportCommand.class, TranspileCommand.class,
                FixCommand.class, RulesCommand.class, SaveCommand.class,
                DecodeCommand.class})
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
     * Pins the character encoding of standard output and standard error to UTF-8. From JDK 18
     * onward, the default charset ({@code file.encoding}) is UTF-8, while the standard output
     * charset ({@code stdout.encoding}) remains the runtime environment's native encoding.
     * Picocli uses the former via PrintWriter, while subcommands use the latter via
     * {@code System.out.println}, so without pinning both, output from the same run mixes two
     * different charsets. The GUI reads this standard output as UTF-8.
     */
    private static void useUtf8Streams() {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true,
                StandardCharsets.UTF_8));
    }
}
