package jp.cobolinsight.core.callgraph;

/** The 11 node kinds of the call graph. */
public enum NodeKind {
    /** A JCL JOB unit. */
    JOB,
    /** A JCL EXEC step unit. */
    STEP,
    /** A COBOL program (PROGRAM-ID). */
    PROGRAM,
    /** A COBOL paragraph or section. */
    PARAGRAPH,
    /** A dataset referenced by a DD statement. */
    DATASET,
    /** A Db2 table or view referenced by embedded SQL. */
    DB2_TABLE,
    /** A dynamic CALL target that constant propagation cannot resolve (holds the named variable in its attributes). */
    UNRESOLVED,
    /** DFSORT, IDCAMS, IEBGENER, and external leaves for PL/I and Assembler (holds a kind tag in its attributes). */
    EXTERNAL_UTILITY,
    /** A CICS transaction (transaction ID). */
    TRANSACTION,
    /** A BMS mapset or map. */
    BMS_MAP,
    /**
     * An asset whose call relationships could not be read because decoding or parsing failed
     * (holds the relative path and failure reason in its attributes). Since its call relationships
     * are unknown, it is placed as an isolated node with no edges, to prevent the diagram from being
     * misread as representing the whole picture.
     */
    UNANALYZABLE
}
