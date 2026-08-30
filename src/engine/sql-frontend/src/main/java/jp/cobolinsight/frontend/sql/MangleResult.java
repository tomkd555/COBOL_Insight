package jp.cobolinsight.frontend.sql;

/** Result of host variable mangling. One of two outcomes: success or "not analyzable". */
public sealed interface MangleResult {

    /** Mangling succeeded. */
    record Mangled(MangledSql sql) implements MangleResult {
    }

    /** Mangling was not possible. The target SQL is reported as not analyzable. */
    record NotAnalyzable(String reason) implements MangleResult {
    }
}
