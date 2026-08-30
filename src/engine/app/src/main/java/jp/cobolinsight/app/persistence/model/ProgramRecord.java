package jp.cobolinsight.app.persistence.model;

/** One row of the PROGRAM table (a COBOL program, its PROGRAM-ID). */
public record ProgramRecord(long id, long sourceId, String programIdName) {

    public ProgramRecord {
        if (programIdName == null || programIdName.isBlank()) {
            throw new IllegalArgumentException("programIdName must not be blank");
        }
    }
}
