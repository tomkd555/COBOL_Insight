package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * EXEC SQL / EXEC CICS の埋め込みブロック。text は抽出テキスト。operands は CICS コマンドの
 * 主要オペランド(MAP・MAPSET・PROGRAM・TRANSID など。キー昇順)を保持する。
 */
public record EmbeddedBlock(EmbeddedBlockKind kind, String text, Map<String, String> operands,
        SourceRange range) {

    public EmbeddedBlock {
        Objects.requireNonNull(kind, "kind");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        operands = Collections.unmodifiableSortedMap(new TreeMap<>(operands));
        Objects.requireNonNull(range, "range");
    }
}
