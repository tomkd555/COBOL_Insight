package jp.cobolinsight.transpile.emit;

import java.util.Locale;
import java.util.Set;

/** 88レベル VALUE 句の値 literal の判定、引用符除去、表意定数と THRU 範囲の解釈。 */
public final class Literals {

    /** VALUE の範囲指定を区切る語。前後の空白を含めて探す。 */
    private static final String THRU = " THRU ";

    /**
     * バイト値が文字コード系(EBCDIC/ASCII)に依存する表意定数。値へ写すと原意と食い違うため
     * 対訳しない。手続き部の被演算子の扱い({@code OperandParser})と同じ区分である。
     */
    private static final Set<String> CODE_PAGE_DEPENDENT = Set.of(
            "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE", "LOW-VALUES", "QUOTE", "QUOTES",
            "NULL", "NULLS");

    private Literals() {
    }

    /** 文字コード系に依存し、値へ写せない表意定数か。 */
    public static boolean isCodePageDependentFigurative(String value) {
        return CODE_PAGE_DEPENDENT.contains(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 比較式へ埋め込む値。表意定数は ZERO を 0、SPACE を半角空白1文字へ写し、それ以外は引用符を
     * 外した中身を返す。SPACES の1文字への簡約は手続き部の対訳と同じ扱いである。
     */
    public static String resolve(String value) {
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "ZERO", "ZEROS", "ZEROES" -> "0";
            case "SPACE", "SPACES" -> " ";
            default -> unquote(value);
        };
    }

    /**
     * 範囲指定を区切る THRU の位置。無ければ -1。引用符の中の THRU は文字列 literal の一部であり
     * 区切りではないため読み飛ばす。
     */
    public static int indexOfThru(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        char quote = 0;
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (upper.startsWith(THRU, i)) {
                return i;
            }
        }
        return -1;
    }

    /** 範囲指定の THRU の文字数(前後の空白を含む)。 */
    public static int thruLength() {
        return THRU.length();
    }

    /** 単引用符または二重引用符で囲まれた文字列 literal か。 */
    public static boolean isQuoted(String value) {
        String v = value.trim();
        if (v.length() < 2) {
            return false;
        }
        char first = v.charAt(0);
        char last = v.charAt(v.length() - 1);
        return (first == '\'' || first == '"') && first == last;
    }

    /** 引用符を除去した中身を返す。引用符が無ければそのまま返す。 */
    public static String unquote(String value) {
        String v = value.trim();
        if (isQuoted(v)) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
