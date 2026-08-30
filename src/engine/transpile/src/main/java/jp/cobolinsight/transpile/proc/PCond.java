package jp.cobolinsight.transpile.proc;

/**
 * Intermediate representation of a procedure statement's condition expression: five kinds—
 * relational comparison, logical AND, logical OR, negation, and untranslatable. The
 * language-specific textual form (string comparison via {@code .equals}, logical operators,
 * negation) is supplied by {@link ExprWriter} through {@link ProcedureDialect}.
 */
public sealed interface PCond permits PCond.Rel, PCond.And, PCond.Or, PCond.Negate, PCond.Raw {

    /**
     * Returns an annotation note for an untranslatable condition ({@link Raw}), making it visible
     * by including the original text. Returns an empty string for a condition that can be
     * translated directly. Used in common across the construction sites for IF and WHILE
     * (inline/out-of-line PERFORM UNTIL/VARYING).
     */
    static String untranslatableNote(PCond cond) {
        return cond instanceof Raw raw
                ? "条件に未宣言項目/特殊レジスタを含み直訳不能: " + raw.text()
                : "";
    }

    /** Relational comparison. When stringCompare is true, this is a string comparison ({@code .equals}/{@code compareTo} depending on the language). */
    record Rel(PExpr left, RelOp op, PExpr right, boolean stringCompare) implements PCond {
    }

    record And(PCond left, PCond right) implements PCond {
    }

    record Or(PCond left, PCond right) implements PCond {
    }

    record Negate(PCond inner) implements PCond {
    }

    /**
     * A condition that cannot be translated directly. Retains the original text; the generated
     * form is reduced to a tautology and made visible via the note.
     */
    record Raw(String text) implements PCond {
    }
}
