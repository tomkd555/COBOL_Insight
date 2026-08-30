package jp.cobolinsight.app.persistence.model;

import java.util.Objects;

/** One row of the LINE_MAP table (the mapping between a COBOL line and a generated line). note is an annotation for syntax that cannot be translated verbatim, or an empty string if it can. */
public record LineMapRecord(long id, long cobolSourceId, int cobolLineStart, int cobolLineEnd,
        String genFile, int genLineStart, int genLineEnd, String kind, String note, String anchorId) {

    public LineMapRecord {
        if (genFile == null || genFile.isBlank()) {
            throw new IllegalArgumentException("genFile must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        Objects.requireNonNull(note, "note");
        if (anchorId == null || anchorId.isBlank()) {
            throw new IllegalArgumentException("anchorId must not be blank");
        }
    }
}
