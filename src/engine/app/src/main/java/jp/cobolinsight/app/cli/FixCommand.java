package jp.cobolinsight.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Parent of the `fix` subcommand. Handles standardized fix proposals for detection results.
 * Displaying the diff is handled by the child command {@code preview}, and outputting the fixed
 * source is handled by the child command {@code apply}. Shows usage when invoked without a child command.
 */
@Command(name = "fix", mixinStandardHelpOptions = true,
        description = "検出結果への修正案を差分表示(preview)・修正後ソース出力(apply)する",
        subcommands = {FixPreviewCommand.class, FixApplyCommand.class})
public final class FixCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}
