package jp.cobolinsight.transpile.proc;

import java.util.List;

/**
 * Intermediate representation of a procedure statement's value expression: four kinds—data
 * reference, literal, arithmetic operator, and arithmetic token sequence. The language-specific
 * textual form is supplied by {@link ExprWriter} through {@link ProcedureDialect}. Subscripts are
 * kept in 1-based COBOL notation as-is; the adjustment to 0-based indexing is done on the
 * language side.
 */
public sealed interface PExpr permits PExpr.Ref, PExpr.Lit, PExpr.Op, PExpr.Arith {

    /** Reference to a data item. fieldName is the field name on the generated side, isString indicates whether it is a string type, and subscripts holds the subscripts (empty means a simple reference). */
    record Ref(String fieldName, boolean isString, List<PExpr> subscripts) implements PExpr {
        public Ref {
            subscripts = List.copyOf(subscripts);
        }
    }

    /** A literal. When isString is true this is a string (value holds the content with quotes removed); when false it is numeric (value holds the original notation). */
    record Lit(String value, boolean isString) implements PExpr {
    }

    /** An operator or parenthesis in an arithmetic token sequence (kept verbatim). */
    record Op(String symbol) implements PExpr {
    }

    /** An arithmetic expression: a token sequence listing operands (Ref/Lit) and operators (Op) in their original order. */
    record Arith(List<PExpr> parts) implements PExpr {
        public Arith {
            parts = List.copyOf(parts);
        }
    }
}
