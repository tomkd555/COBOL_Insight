package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.picture.PictureCategory;
import jp.cobolinsight.core.picture.PictureType;

/**
 * アクセサが値とバイト列を変換する際の符号化区分。基本項目の PICTURE と USAGE から決まり、
 * ランタイムヘルパの decode/encode 関数と1対1で対応する。
 */
public enum FieldKind {
    /** COMP-3 パック10進。 */
    PACKED,
    /** DISPLAY ゾーン10進。 */
    ZONED,
    /** BINARY(COMP/COMP-4/COMP-5)。 */
    BINARY,
    /** 英数字・英字・編集項目(バイト列をそのまま文字列として扱う)。 */
    ALPHANUMERIC;

    public static FieldKind of(PictureType type) {
        if (type.category() == PictureCategory.NUMERIC) {
            return switch (type.usage()) {
                case PACKED_DECIMAL -> PACKED;
                case BINARY -> BINARY;
                case DISPLAY -> ZONED;
            };
        }
        return ALPHANUMERIC;
    }
}
