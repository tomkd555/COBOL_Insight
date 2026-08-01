package jp.cobolinsight.engineapi.picture;

/** PICTURE 句が表すデータのカテゴリ。 */
public enum PictureCategory {
    NUMERIC,
    NUMERIC_EDITED,
    ALPHANUMERIC,
    ALPHANUMERIC_EDITED,
    ALPHABETIC,
    /** 種別を判別できる記号が PICTURE 句に現れなかった場合。 */
    UNKNOWN
}
