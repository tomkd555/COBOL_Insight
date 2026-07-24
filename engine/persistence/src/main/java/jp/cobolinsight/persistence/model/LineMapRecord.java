package jp.cobolinsight.persistence.model;

import java.util.Objects;

/** LINE_MAP表の1行(COBOL行と生成行の対応)。note は直訳不能構文の注記で、直訳可能なら空文字列。 */
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
