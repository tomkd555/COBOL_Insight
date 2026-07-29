package jp.cobolinsight.engineapi.sql;

/** 埋め込みSQL文の種別。 */
public enum SqlStatementKind {
    /** SELECT。単独のSELECTと SELECT INTO の双方を含む。 */
    SELECT,
    INSERT,
    UPDATE,
    DELETE,
    DECLARE_CURSOR,
    OPEN,
    FETCH,
    CLOSE,
    /** 上記以外の文と、構文解析できなかった文。 */
    OTHER
}
