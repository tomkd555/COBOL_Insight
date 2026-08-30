package jp.cobolinsight.core.linemap;

import jp.cobolinsight.core.source.LineRange;

import java.util.Objects;

/**
 * One row of the line-by-line translation mapping table. Bidirectionally maps COBOL lines to
 * generated lines (Python/Java). note is a remark for constructs that cannot be translated
 * literally, and is an empty string when a literal translation is possible. anchorId is the
 * anchor used for GUI links.
 */
public record LineMappingEntry(String cobolSourceId, LineRange cobolLines, String generatedFile,
        LineRange generatedLines, MappingKind mappingKind, String note, String anchorId) {

    public LineMappingEntry {
        if (cobolSourceId == null || cobolSourceId.isBlank()) {
            throw new IllegalArgumentException("cobolSourceId must not be blank");
        }
        Objects.requireNonNull(cobolLines, "cobolLines");
        if (generatedFile == null || generatedFile.isBlank()) {
            throw new IllegalArgumentException("generatedFile must not be blank");
        }
        Objects.requireNonNull(generatedLines, "generatedLines");
        Objects.requireNonNull(mappingKind, "mappingKind");
        Objects.requireNonNull(note, "note");
        if (anchorId == null || anchorId.isBlank()) {
            throw new IllegalArgumentException("anchorId must not be blank");
        }
    }
}
