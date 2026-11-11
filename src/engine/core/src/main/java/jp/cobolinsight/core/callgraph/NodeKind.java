package jp.cobolinsight.core.callgraph;

/** The node kinds of the call graph. */
public enum NodeKind {
    /** A JCL JOB unit. */
    JOB,
    /** A JCL EXEC step unit. */
    STEP,
    /**
     * A program: a COBOL program (PROGRAM-ID), an Easytrieve member (its {@code language} attribute
     * says so), a routine an SQL script defines, or a load module nothing in the walk is the source
     * of.
     */
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
    /** An IMS program specification block: what an IMS region starts a program with. */
    IMS_PSB,
    /** An IMS database, named by its DBD. */
    IMS_DATABASE,
    /** An IBM MQ queue a program opens by a name written in the source. */
    MQ_QUEUE,
    /**
     * An asset whose call relationships could not be read because decoding or parsing failed
     * (holds the relative path and failure reason in its attributes). Since its call relationships
     * are unknown, it is placed as an isolated node with no edges, to prevent the diagram from being
     * misread as representing the whole picture.
     */
    UNANALYZABLE
}
