package jp.cobolinsight.cli;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * コピー句を COPY 文で取り込むプログラムを解決する。コピー句由来の修正は原本コピー句を書き換えず
 * 差分提示に留めるため、影響範囲として当該コピー句を取り込む全プログラムを併記する必要がある。
 * その一覧を、復号済みプログラムソースの COPY 文走査で決定論的に求める。
 */
final class CopybookImporters {

    private CopybookImporters() {
    }

    /**
     * {@code copybookBaseName}(拡張子を除く基底名、大小無視)を COPY 文で取り込むプログラムの
     * 相対パスを昇順で返す。{@code programSourcesByRel} はプログラム相対パス→復号済みソーステキスト。
     * REPLACING を伴う COPY も対象とする。
     */
    static List<String> of(String copybookBaseName, Map<String, String> programSourcesByRel) {
        // 直後が COBOL の語構成文字(英数字・ハイフン・下線・$・#)なら、より長い別名の前方一致
        // でしかないため対象から外す。
        Pattern copy = Pattern.compile(
                "(?i)\\bCOPY\\s+" + Pattern.quote(copybookBaseName) + "(?![\\p{L}\\p{N}$#_-])");
        return programSourcesByRel.entrySet().stream()
                .filter(entry -> copy.matcher(entry.getValue()).find())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** 相対パスから拡張子を除いた基底名(コピー句名)を取り出す。 */
    static String baseName(String relPath) {
        String name = relPath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
