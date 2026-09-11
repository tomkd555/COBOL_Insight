package jp.cobolinsight.app.persistence.model;

/**
 * One row of the SQL_STMT table: an embedded SQL statement of a COBOL source, or a statement of an
 * SQL script. {@code programId} is null where the source held no PROGRAM row, which is every
 * statement of a script; {@code analysis} is FULL or DEGRADED, {@code text} is the statement as the
 * source spells it, and {@code detailJson} holds the remaining facts the SQL frontend read off the
 * parse tree.
 *
 * <p>{@code line} and {@code endLine} are lines of {@code file}, which is the file the statement
 * stands in as SOURCE.path spells it. That is the program itself as a rule, and the copybook for a
 * statement the program takes in through a COPY; reading the lines against the source alone would
 * point at the wrong text in that case.
 */
public record SqlStatementRecord(long id, long sourceId, Long programId, int seq, String kind,
        String cursorName, int line, int endLine, String analysis, String text, String file,
        String detailJson) {

    public SqlStatementRecord {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (analysis == null || analysis.isBlank()) {
            throw new IllegalArgumentException("analysis must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        if (detailJson == null) {
            throw new IllegalArgumentException("detailJson must not be null");
        }
    }
}
