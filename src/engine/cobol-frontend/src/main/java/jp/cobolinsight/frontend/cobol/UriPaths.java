package jp.cobolinsight.frontend.cobol;

import java.net.URI;
import java.nio.file.Paths;

/** URI とファイルパス表現の相互変換。 */
final class UriPaths {

    private UriPaths() {
    }

    /**
     * Che4z が暗黙に差し込むコード(SQLCA など)の URI か。実ファイルが無いため、原文の取得や
     * コピー句展開の対応表からは除く。
     */
    static boolean isImplicit(String uri) {
        return uri == null || uri.startsWith("implicit:") || uri.contains("implicit-code");
    }

    /** file URI をファイルシステムのパス文字列へ変換する。変換できない場合は URI のまま返す。 */
    static String toPathString(String uri) {
        try {
            return Paths.get(new URI(uri)).toString();
        } catch (Exception e) {
            return uri;
        }
    }
}
