package jp.cobolinsight.app.persistence.model;

/** BMS_FIELD表の1行(DFHMDFが定義する項目)。 */
public record BmsFieldRecord(long id, long mapId, String name, int posRow, int posCol, int length,
        String attrb) {

    public BmsFieldRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
