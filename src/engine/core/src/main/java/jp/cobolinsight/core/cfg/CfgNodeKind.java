package jp.cobolinsight.core.cfg;

/** CFG node kinds. */
public enum CfgNodeKind {
    /** Program entry. Has no statement. */
    ENTRY,
    /** Program exit. Has no statement. */
    EXIT,
    /** A single statement in the Procedure Division (a compound statement keeps its condition as a node separate from its body statements). */
    STATEMENT
}
