package jp.cobolinsight.app.persistence.model;

/** One row of the BMS_FIELD table (a field defined by DFHMDF). */
public record BmsFieldRecord(long id, long mapId, String name, int posRow, int posCol, int length,
        String attrb) {

    public BmsFieldRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
