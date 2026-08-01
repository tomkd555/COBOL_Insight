package jp.cobolinsight.persistence.model;

/** BMS_MAPSET表の1行(DFHMSDが定義する、複数のマップをまとめる単位)。 */
public record BmsMapsetRecord(long id, long sourceId, String name) {

    public BmsMapsetRecord {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
