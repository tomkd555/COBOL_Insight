package jp.cobolinsight.engineapi.picture;

import java.util.Locale;

/**
 * バイト長算出に関わる USAGE の正規化区分。COMP-3/COMPUTATIONAL-3/PACKED-DECIMAL を
 * {@link #PACKED_DECIMAL} に、BINARY/COMP/COMP-4/COMP-5 等を {@link #BINARY} にまとめる。
 * 該当しない USAGE(無指定・浮動小数点 COMP-1/COMP-2 等)は {@link #DISPLAY} として扱う。
 */
public enum Usage {
    DISPLAY,
    PACKED_DECIMAL,
    BINARY;

    /** USAGE 文字列を別名解決して正規化する。無指定・空は DISPLAY。 */
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
