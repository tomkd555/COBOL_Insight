package jp.cobolinsight.core.rule;

/**
 * The CLI subcommands a rule can run under. A rule declares the set it belongs to in
 * {@link RuleMeta#commands()}, and a command asks the rule set only for its own members.
 */
public enum Command {
    /** {@code lint}: bug detection over COBOL, copybooks and BMS. */
    LINT,
    /** {@code sql-lint}: advice on embedded SQL. */
    SQL_LINT,
    /** {@code report}: the combined report. */
    REPORT,
    /** {@code fix preview} and {@code fix apply}: rules whose findings carry a fix. */
    FIX,
    /** {@code scan}: rules evaluated while the asset folder is persisted. */
    SCAN
}
