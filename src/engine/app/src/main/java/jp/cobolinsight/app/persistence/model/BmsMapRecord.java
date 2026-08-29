package jp.cobolinsight.app.persistence.model;

/** BMS_MAP表の1行(DFHMDIが定義する画面)。 */
public record BmsMapRecord(long id, long mapsetId, String name, int sizeRows, int sizeCols) {

    public BmsMapRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
