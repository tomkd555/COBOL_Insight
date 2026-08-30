package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.dataflow.ValueInterval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A recursive-descent evaluator that evaluates a COMPUTE right-hand side's simple arithmetic
 * expression over intervals. Handles addition, subtraction, multiplication, division,
 * parentheses, and unary sign, with precedence multiplication/division &gt; addition/subtraction.
 * When it hits an unsupported token or a syntax inconsistency, evaluation falls back to an
 * interval unbounded on both sides. Resolving a data name's interval is delegated to the
 * caller's resolver (unbounded when untracked).
 */
final class IntervalExpr {

    private final List<String> tokens;
    private final Function<String, ValueInterval> resolver;
    private int pos;

    private IntervalExpr(List<String> tokens, Function<String, ValueInterval> resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    /** Evaluates the expression. Returns an interval unbounded on both sides if tokenization or parsing fails. */
    static ValueInterval eval(String expr, Function<String, ValueInterval> resolver) {
        List<String> tokens = tokenize(expr);
        if (tokens == null) {
            return Intervals.UNBOUNDED;
        }
        IntervalExpr parser = new IntervalExpr(tokens, resolver);
        try {
            ValueInterval value = parser.expression();
            if (parser.pos != tokens.size()) {
                return Intervals.UNBOUNDED;
            }
            return value;
        } catch (ParseFailure e) {
            return Intervals.UNBOUNDED;
        }
    }

    private static final class ParseFailure extends RuntimeException {
    }

    private ValueInterval expression() {
        ValueInterval value = term();
        while (pos < tokens.size()) {
            String op = tokens.get(pos);
            if (op.equals("+")) {
                pos++;
                value = Intervals.add(value, term());
            } else if (op.equals("-")) {
                pos++;
                value = Intervals.sub(value, term());
            } else {
                break;
            }
        }
        return value;
    }

    private ValueInterval term() {
        ValueInterval value = factor();
        while (pos < tokens.size()) {
            String op = tokens.get(pos);
            if (op.equals("*")) {
                pos++;
                value = Intervals.mul(value, factor());
            } else if (op.equals("/")) {
                pos++;
                value = Intervals.div(value, factor());
            } else {
                break;
            }
        }
        return value;
    }

    private ValueInterval factor() {
        if (pos >= tokens.size()) {
            throw new ParseFailure();
        }
        String tok = tokens.get(pos);
        if (tok.equals("-")) {
            pos++;
            return Intervals.sub(ValueInterval.point(0), factor());
        }
        if (tok.equals("+")) {
            pos++;
            return factor();
        }
        if (tok.equals("(")) {
            pos++;
            ValueInterval inner = expression();
            if (pos >= tokens.size() || !tokens.get(pos).equals(")")) {
                throw new ParseFailure();
            }
            pos++;
            return inner;
        }
        if (tok.equals(")") || tok.equals("*") || tok.equals("/")) {
            throw new ParseFailure();
        }
        pos++;
        return resolver.apply(tok);
    }

    /** Splits on whitespace into numbers, data names, and operators (+ - * / ( )). An unknown character is a failure (null). */
    private static List<String> tokenize(String expr) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < n && Character.isDigit(expr.charAt(j))) {
                    j++;
                }
                out.add(expr.substring(i, j));
                i = j;
            } else if (Character.isLetter(c)) {
                int j = i;
                while (j < n) {
                    char d = expr.charAt(j);
                    if (Character.isLetterOrDigit(d) || d == '-') {
                        j++;
                    } else {
                        break;
                    }
                }
                out.add(expr.substring(i, j));
                i = j;
            } else {
                return null;
            }
        }
        return out;
    }
}
