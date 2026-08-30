package jp.cobolinsight.app.persistence.model;

/** One row of the PARAGRAPH table (a COBOL paragraph or section). */
public record ParagraphRecord(long id, long programId, String name, int startLine, int endLine) {

    public ParagraphRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
