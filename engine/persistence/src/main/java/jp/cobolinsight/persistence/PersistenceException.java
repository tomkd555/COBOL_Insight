package jp.cobolinsight.persistence;

/** 永続化層の入出力エラーを表す非検査例外。 */
public class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceException(String message) {
        super(message);
    }
}
