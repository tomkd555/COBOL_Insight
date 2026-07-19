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
