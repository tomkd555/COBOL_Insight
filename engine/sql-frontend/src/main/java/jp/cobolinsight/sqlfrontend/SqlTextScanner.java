package jp.cobolinsight.sqlfrontend;

/** SQLテキスト走査の共通処理。 */
final class SqlTextScanner {

    private SqlTextScanner() {
    }

    /**
     * open の位置の引用符で始まる文字列リテラルの終端引用符の位置を返す。
     * '' のエスケープを考慮する。閉じていなければ -1 を返す。
     */
    static int findStringEnd(String text, int open) {
        int i = open + 1;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i;
            }
            i++;
        }
        return -1;
    }

    /**
     * コメント({@code --} 行コメントと {@code /*} ブロックコメント)の中身を空白で潰した
     * 同じ長さのテキストを返す(オフセット保存)。改行は残す。コメント内のアポストロフィを
     * 文字列リテラルの開始と誤認すること、および空白の正規化で改行を失った行コメントが
     * 以降の本文を飲み込むことを、いずれもこの前処理で防ぐ。
     */
    static String maskComments(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\'') {
                int close = findStringEnd(text, i);
                i = close < 0 ? text.length() : close + 1;
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.setCharAt(i, ' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end;
                for (int j = i + 2; j < stop; j++) {
                    if (out.charAt(j) != '\n') {
                        out.setCharAt(j, ' ');
                    }
                }
                i = end < 0 ? text.length() : end + 2;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    /** 文字列リテラルの中身を空白で潰した同じ長さのテキストを返す(オフセット保存)。 */
    static String maskStringLiterals(String text) {
        StringBuilder out = new StringBuilder(text);
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == '\'') {
                int close = findStringEnd(text, i);
                int end = close < 0 ? text.length() : close;
                for (int j = i + 1; j < end; j++) {
                    out.setCharAt(j, ' ');
                }
                i = close < 0 ? text.length() : close + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }
}
