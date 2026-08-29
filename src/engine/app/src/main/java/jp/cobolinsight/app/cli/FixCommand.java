package jp.cobolinsight.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * `fix` サブコマンドの親。検出結果に対する定型的な修正案を扱う。差分の表示は子コマンド
 * {@code preview}、修正後ソースの出力は子コマンド {@code apply} が担う。子コマンド無しで
 * 呼ばれた場合は使用方法を表示する。
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
