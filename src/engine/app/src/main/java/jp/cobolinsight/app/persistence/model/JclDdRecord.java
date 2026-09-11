package jp.cobolinsight.app.persistence.model;

/**
 * One row of the JCL_DD table (a DD statement of a step). {@code seq} is a 1-based number running
 * over the DD statements of the step, so a concatenation keeps its order; {@code dsn} is the data
 * set the DD names, member and relative generation included, and is null for a DD that names none.
 * {@code access} is how the step uses the DD, as {@code JclUtilityFacts.DatasetAccess} names it.
 */
public record JclDdRecord(long id, long stepId, int seq, String ddName, String dsn, String access,
        Integer line, String file, String detailJson) {

    public JclDdRecord {
        if (ddName == null || ddName.isBlank()) {
            throw new IllegalArgumentException("ddName must not be blank");
        }
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (detailJson == null) {
            throw new IllegalArgumentException("detailJson must not be null");
        }
    }
}
