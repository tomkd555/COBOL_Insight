package jp.cobolinsight.core.picture;

import java.util.Locale;

/**
 * The normalized USAGE category relevant to byte-length computation. Groups COMP-3,
 * COMPUTATIONAL-3, and PACKED-DECIMAL under {@link #PACKED_DECIMAL}, and BINARY, COMP, COMP-4,
 * COMP-5, etc. under {@link #BINARY}. Any USAGE that does not match (unspecified, floating-point
 * COMP-1/COMP-2, etc.) is treated as {@link #DISPLAY}.
 */
public enum Usage {
    DISPLAY,
    PACKED_DECIMAL,
    BINARY;

    /** Resolves aliases in a USAGE string and normalizes it. Unspecified/empty maps to DISPLAY. */
    public static Usage normalize(String usage) {
        if (usage == null || usage.isBlank()) {
            return DISPLAY;
        }
        String canonical = usage.trim().toUpperCase(Locale.ROOT)
                .replace("COMPUTATIONAL", "COMP")
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return switch (canonical) {
            case "COMP3", "PACKEDDECIMAL" -> PACKED_DECIMAL;
            case "BINARY", "COMP", "COMP4", "COMP5" -> BINARY;
            default -> DISPLAY;
        };
    }
}
