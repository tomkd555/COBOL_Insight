package jp.cobolinsight.app.persistence;

/** Unchecked exception representing an I/O error in the persistence layer. */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
