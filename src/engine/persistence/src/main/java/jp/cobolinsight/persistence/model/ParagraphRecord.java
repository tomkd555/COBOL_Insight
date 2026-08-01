package jp.cobolinsight.persistence.model;

/** PARAGRAPH表の1行(COBOLの段落・節)。 */
public record ParagraphRecord(long id, long programId, String name, int startLine, int endLine) {

    public ParagraphRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
