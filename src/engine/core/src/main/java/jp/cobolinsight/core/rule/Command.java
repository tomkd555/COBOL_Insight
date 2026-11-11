package jp.cobolinsight.core.rule;

/**
 * The CLI subcommands a rule can run under. A rule declares the set it belongs to in
 * {@link RuleMeta#commands()}, and a command asks the rule set only for its own members.
 */
public enum Command {
    /**
     * {@code lint}: defect detection over every asset kind — COBOL and copybooks, JCL and the
     * control statements of its steps, SQL scripts, BMS, Easytrieve and the IMS generations.
     */
    LINT,
    /** {@code lint}'s SQL advice side: advice on the SQL of programs and scripts. */
    SQL_LINT,
    /** {@code fix}: rules whose findings carry a fix. */
    FIX
}
