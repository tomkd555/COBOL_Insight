package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Failures;
import jp.cobolinsight.core.pipeline.ExitCodes;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** The main entry point for the group of picocli subcommands. */
@Command(name = "cobol-insight", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = "COBOL資産の統合解析ツール",
        subcommands = {ScanCommand.class, LintCommand.class,
                ReportCommand.class, TranspileCommand.class,
                FixCommand.class, RulesCommand.class, SaveCommand.class,
                DecodeCommand.class})
public final class Main implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /**
     * The version {@code --version} prints: the Implementation-Version the jar task stamps from
     * gradle.properties. Run from a classes directory (the tests), there is no manifest and the
     * word "development" stands in.
     */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = Main.class.getPackage().getImplementationVersion();
            return new String[] {"COBOL Insight " + (version == null ? "development" : version)};
        }
    }

    public static void main(String[] args) {
        useUtf8Streams();
        System.exit(commandLine().execute(args));
    }

    /**
     * The configured command line, with every subcommand plus the two handlers that turn a failure
     * into one Japanese line on stderr: an uncaught exception instead of a stack trace, and a
     * misused option instead of the whole usage block. The GUI shows this stream in its run log,
     * where twenty-odd lines of command-line help would bury the one line that explains the
     * problem. Package-private so {@code MainCommandTest} can exercise the handlers directly.
     */
    static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            System.err.println("エラー: " + Failures.describe(ex) + "。処理を中止しました。");
            return ExitCodes.ERRORS;
        });
        cmd.setParameterExceptionHandler((ex, args) -> {
            System.err.println("エラー: " + ex.getMessage());
            return ex.getCommandLine().getCommandSpec().exitCodeOnInvalidInput();
        });
        return cmd;
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
