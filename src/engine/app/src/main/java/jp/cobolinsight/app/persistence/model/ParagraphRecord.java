package jp.cobolinsight.app.persistence.model;

/**
 * One row of the PARAGRAPH table (a COBOL paragraph or section). kind is SECTION or PARAGRAPH;
 * sectionName is the section a paragraph belongs to, null for a section and for a paragraph
 * outside any section.
 */
public record ParagraphRecord(long id, long programId, String name, String kind,
        String sectionName, int startLine, int endLine) {

    public ParagraphRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
