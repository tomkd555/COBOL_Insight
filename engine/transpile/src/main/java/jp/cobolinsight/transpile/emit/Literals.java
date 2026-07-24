package jp.cobolinsight.transpile.emit;

/** 88レベル VALUE 句の値 literal の判定と引用符除去。 */
public final class Literals {

    private Literals() {
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
