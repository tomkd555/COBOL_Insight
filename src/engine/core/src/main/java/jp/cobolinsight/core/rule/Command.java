package jp.cobolinsight.core.rule;

/**
 * The CLI subcommands a rule can run under. A rule declares the set it belongs to in
 * {@link RuleMeta#commands()}, and a command asks the rule set only for its own members.
 */
public enum Command {
    /** {@code lint}: bug detection over COBOL, copybooks and BMS. */
    LINT,
    /** {@code lint}'s SQL advice side: advice on embedded SQL. */
    SQL_LINT,
    /** {@code fix}: rules whose findings carry a fix. */
    FIX
}
