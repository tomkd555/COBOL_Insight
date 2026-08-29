package jp.cobolinsight.app.cli;

import jp.cobolinsight.rules.RuleSet;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;

/**
 * The {@code --rules} option, shared by every subcommand as a picocli mixin. One file carries both
 * the per-rule overrides and the custom rule definitions, so there is one place to point at and one
 * place where the resulting rule set is built.
 */
final class RuleOptions {

    @Option(names = "--rules", paramLabel = "FILE",
            description = "ルール設定ファイル(JSON)。有効・無効と重大度の上書き、利用者定義ルールを書く")
    Path rulesFile;

    @Spec
    CommandLine.Model.CommandSpec spec;

    /**
     * Builds the rule set. A file that is not JSON, or whose version this build cannot read, is a
     * usage error: the configuration the author meant is not in effect, and carrying on with the
     * defaults would analyse something other than what was asked for.
     */
    RuleSet ruleSet() {
        try {
            return RuleSet.load(rulesFile);
        } catch (IllegalArgumentException e) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "--rules: " + e.getMessage());
        }
    }

    /** The rule set, with the file's per-entry problems written to standard error. */
    RuleSet reportingRuleSet() {
        RuleSet ruleSet = ruleSet();
        for (String error : ruleSet.errors()) {
            System.err.println("警告: ルール設定: " + error);
        }
        return ruleSet;
    }
}
