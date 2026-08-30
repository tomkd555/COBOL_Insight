package jp.cobolinsight.app.persistence.model;

/** One row of the BMS_MAP table (a screen defined by DFHMDI). */
public record BmsMapRecord(long id, long mapsetId, String name, int sizeRows, int sizeCols) {

    public BmsMapRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
