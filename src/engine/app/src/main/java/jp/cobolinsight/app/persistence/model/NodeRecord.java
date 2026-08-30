package jp.cobolinsight.app.persistence.model;

/**
 * One row of the NODE table (a node of the call-relationship graph). A node that corresponds to a
 * source is registered with NODE.id = SOURCE.id; a node with no corresponding source (a job step,
 * a dataset, etc.) is assigned an ID larger than that.
 */
public record NodeRecord(long id, String type, String label) {

    public NodeRecord {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
    }
}
