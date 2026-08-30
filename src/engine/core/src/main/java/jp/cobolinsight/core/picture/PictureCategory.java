package jp.cobolinsight.core.picture;

/** The category of data that a PICTURE clause represents. */
public enum PictureCategory {
    NUMERIC,
    NUMERIC_EDITED,
    ALPHANUMERIC,
    ALPHANUMERIC_EDITED,
    ALPHABETIC,
    /** When no symbol that determines the kind appears in the PICTURE clause. */
    UNKNOWN
}
