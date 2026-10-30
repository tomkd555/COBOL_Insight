package jp.cobolinsight.app.persistence.model;

/**
 * One row of the PARAGRAPH_EDGE table (a flow out of a paragraph within a program). kind is one
 * of PERFORM, GOTO, FALLTHROUGH, CALL, or XCTL, where GOTO is a GO TO statement or a GO TO
 * written in a phrase of another statement, FALLTHROUGH represents falling through to
 * the next paragraph in definition order, and CALL and XCTL leave the program (a CALL statement
 * or an EXEC CICS LINK, and an EXEC CICS XCTL).
 * toParagraph is null when the destination paragraph cannot be identified, and always for CALL
 * and XCTL, in which case toName still retains the original name (the program name, or the
 * data-name a dynamic call is written with).
 * thruName is the THRU end of a PERFORM … THRU or a SORT/MERGE procedure, null otherwise;
 * thruParagraph is its paragraph where it resolves to the target or a paragraph after it, and null
 * where it resolves to none or to one ahead of the target, as ProcedureNames.rangeOf reads it.
 * line is the line of the PERFORM, GO TO, CALL or EXEC CICS statement (null for fallthrough);
 * seq is this edge's position among the outgoing edges of the from paragraph.
 */
public record ParagraphEdgeRecord(long id, long programSourceId, long fromParagraph,
        Long toParagraph, String toName, Long thruParagraph, String thruName, String kind,
        Integer line, int seq) {

    public ParagraphEdgeRecord {
        if (toName == null || toName.isBlank()) {
            throw new IllegalArgumentException("toName must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
    }
}
