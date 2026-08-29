package jp.cobolinsight.app.persistence.model;

/**
 * One row of the PARAGRAPH_EDGE table (a paragraph-to-paragraph flow within a program). kind is
 * one of PERFORM, GOTO, or FALLTHROUGH, where FALLTHROUGH represents falling through to the
 * next paragraph in definition order.
 * toParagraph is null when the destination paragraph cannot be identified, in which case toName
 * still retains the original name.
 * line is the line of the PERFORM or GO TO statement (null for fallthrough); seq is this edge's
 * position among the outgoing edges of the from paragraph.
 */
public record ParagraphEdgeRecord(long id, long programSourceId, long fromParagraph,
        Long toParagraph, String toName, String kind, Integer line, int seq) {

    public ParagraphEdgeRecord {
        if (toName == null || toName.isBlank()) {
            throw new IllegalArgumentException("toName must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
