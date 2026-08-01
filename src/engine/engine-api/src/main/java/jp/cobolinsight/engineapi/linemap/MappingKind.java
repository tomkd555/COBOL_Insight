package jp.cobolinsight.engineapi.linemap;

/** 行対応の種別。COBOL側の行数と生成側の行数の対応を表す。 */
public enum MappingKind {
    /** COBOL 1行と生成1行が対応する。 */
    ONE_TO_ONE,
    /** COBOL 1行に生成側の複数行が対応する。 */
    ONE_TO_MANY,
    /** COBOLの複数行が生成側の1行へ畳まれる。 */
    MANY_TO_ONE
}
