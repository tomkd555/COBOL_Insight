package jp.cobolinsight.rules;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ルール評価時に参照する復号済みソーステキストの索引。キーは意味モデルの sourceFile と同一の
 * パス文字列とし、AnalysisContext の artifact として受け渡す。構文ルールのうち、意味モデルに
 * 現れない字句情報(セクション見出し・COPY文・文字列リテラル)を要するルールが用いる。
 */
public final class SourceTextIndex {

    private final Map<String, String> textByPath;

    public SourceTextIndex(Map<String, String> textByPath) {
        this.textByPath = new TreeMap<>(textByPath);
    }

    /** パス文字列の完全一致でテキストを引く。 */
    public Optional<String> textOf(String path) {
        return Optional.ofNullable(textByPath.get(path));
    }

    /**
     * ファイル名(拡張子を除く基底名)の大小無視一致でテキストを引く。COPY文のコピー句名の
     * 解決に用いる。一致が複数あり内容が異なる場合は empty を返す。
     */
    public Optional<String> textOfBaseName(String baseName) {
        String wanted = baseName.toUpperCase(Locale.ROOT);
        String found = null;
        for (Map.Entry<String, String> entry : textByPath.entrySet()) {
            Path fileName = Path.of(entry.getKey()).getFileName();
            if (fileName == null) {
                continue;
            }
            String name = fileName.toString();
            int dot = name.lastIndexOf('.');
            String base = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
            if (base.equals(wanted)) {
                if (found != null && !found.equals(entry.getValue())) {
                    return Optional.empty();
                }
                found = entry.getValue();
            }
        }
        return Optional.ofNullable(found);
    }

    /** 登録済みの全エントリ(パス昇順)。 */
    public Map<String, String> entries() {
        return java.util.Collections.unmodifiableMap(textByPath);
    }
}
