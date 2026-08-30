package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.picture.PictureCategory;
import jp.cobolinsight.core.picture.PictureType;

/**
 * The encoding kind used when an accessor converts between a value and a byte sequence. Determined
 * by the elementary item's PICTURE and USAGE, and corresponds one-to-one with a runtime helper's
 * decode/encode function.
 */
public enum FieldKind {
    /** COMP-3 packed decimal. */
    PACKED,
    /** DISPLAY zoned decimal. */
    ZONED,
    /** BINARY (COMP/COMP-4/COMP-5). */
    BINARY,
    /** Alphanumeric, alphabetic, and edited items (the byte sequence is treated as a string as-is). */
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
