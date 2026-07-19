package jp.cobolinsight.persistence.model;

/** PROGRAM表の1行(COBOLプログラム、PROGRAM-ID)。 */
public record ProgramRecord(long id, long sourceId, String programIdName) {

    public ProgramRecord {
        if (programIdName == null || programIdName.isBlank()) {
            throw new IllegalArgumentException("programIdName must not be blank");
        }
    }
}
