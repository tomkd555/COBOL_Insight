package jp.cobolinsight.engineapi.finding;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.Objects;

/** ソース範囲を置換する最小編集。replacement が空文字列の場合は削除を表す。 */
public record TextEdit(SourceRange range, String replacement) {

    public TextEdit {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(replacement, "replacement");
    }
}
