package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * An embedded EXEC SQL / EXEC CICS block. text is the extracted text. operands holds the main
 * operands of a CICS command (MAP, MAPSET, PROGRAM, TRANSID, etc., keys in ascending order).
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
