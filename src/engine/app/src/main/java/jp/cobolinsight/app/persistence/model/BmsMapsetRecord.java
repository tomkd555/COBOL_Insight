package jp.cobolinsight.app.persistence.model;

/** A row of the BMS_MAPSET table (the unit, defined by DFHMSD, that groups multiple maps together). */
public record BmsMapsetRecord(long id, long sourceId, String name) {

    public BmsMapsetRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
