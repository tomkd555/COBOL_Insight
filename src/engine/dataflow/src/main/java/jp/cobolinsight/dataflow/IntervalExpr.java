package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.dataflow.ValueInterval;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * COMPUTE 右辺の単純算術式を区間で評価する再帰下降エバリュエータ。加減乗除と括弧・単項符号を
 * 扱い、優先順位は乗除 &gt; 加減。未対応トークン・構文不整合に当たると評価は両側非有界へ退避する。
 * データ名の区間解決は呼出側の resolver に委ねる(未追跡は非有界)。
 */
final class IntervalExpr {

    private final List<String> tokens;
    private final Function<String, ValueInterval> resolver;
    private int pos;

    private IntervalExpr(List<String> tokens, Function<String, ValueInterval> resolver) {
        this.tokens = tokens;
        this.resolver = resolver;
    }

    /** 式を評価する。トークン化・構文で失敗したら両側非有界を返す。 */
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

    /** 空白区切りで数値・データ名・演算子(+ - * / ( ))へ分解する。未知文字は失敗(null)。 */
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
