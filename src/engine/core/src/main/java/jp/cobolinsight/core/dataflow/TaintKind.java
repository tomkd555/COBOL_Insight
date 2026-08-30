package jp.cobolinsight.core.dataflow;

/** Kind of taint tracking. */
public enum TaintKind {
    /** Originates from external input such as screens or reports (ACCEPT, CICS RECEIVE). Consumed by R020. */
    EXTERNAL_INPUT,
    /** Originates from sensitive data (names ending in -SSN, -ACCT-NO, -CARD-NO). Consumed by R027. */
    SENSITIVE
}
