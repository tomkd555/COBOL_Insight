package jp.cobolinsight.core.rule;

/**
 * The technology a rule is about: the language or the middleware whose behaviour makes the finding
 * a defect. It is not the asset kind the finding lands in — a rule about an {@code EXEC CICS}
 * command or a DL/I call reads COBOL source and is still a CICS or an IMS rule, which is how the
 * person who owns that middleware looks for it.
 */
public enum Technology {
    COBOL("COBOL"),
    JCL("JCL"),
    DB2("Db2"),
    CICS("CICS"),
    IMS("IMS"),
    MQ("MQ"),
    EASYTRIEVE("Easytrieve"),
    /** DFSORT, IDCAMS, IEBGENER and the other utilities a step runs from control statements. */
    UTILITY("ユーティリティ");

    private final String label;

    Technology(String label) {
        this.label = label;
    }

    /** The name shown by the GUI and the CLI. */
    public String label() {
        return label;
    }
}
