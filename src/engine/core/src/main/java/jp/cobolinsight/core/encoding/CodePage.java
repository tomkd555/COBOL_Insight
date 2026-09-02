package jp.cobolinsight.core.encoding;

import java.nio.charset.Charset;
import java.util.Locale;

/** Code pages handled by this tool. */
public enum CodePage {
    UTF_8("UTF-8", false),
    /** Shift_JIS. Treated as windows-31j (CP932) so sources containing NEC/IBM extended characters can also be decoded. */
    SHIFT_JIS("windows-31j", false),
    /** Japanese EBCDIC. The SBCS plane holds half-width kana and has no lowercase Latin letters. */
    IBM930("x-IBM930", true),
    /** Japanese EBCDIC. The SBCS plane holds lowercase Latin letters and has no half-width kana. */
    IBM939("x-IBM939", true);

    private final String charsetName;
    private final boolean ebcdic;

    CodePage(String charsetName, boolean ebcdic) {
        this.charsetName = charsetName;
        this.ebcdic = ebcdic;
    }

    /**
     * Resolves a code page name (including aliases). Matching strips non-alphanumeric characters
     * and ignores case, accepting aliases for UTF-8, Shift_JIS, IBM930 and IBM939. An unsupported
     * name throws {@link IllegalArgumentException}.
     */
    public static CodePage fromName(String charsetName) {
        String key = charsetName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "utf8" -> UTF_8;
            case "shiftjis", "sjis", "windows31j", "ms932", "cp932", "932" -> SHIFT_JIS;
            case "ibm930", "xibm930", "cp930", "930" -> IBM930;
            case "ibm939", "xibm939", "cp939", "939" -> IBM939;
            default -> throw new IllegalArgumentException(
                    "コードページ " + charsetName + " には対応していません");
        };
    }

    public String charsetName() {
        return charsetName;
    }

    public Charset charset() {
        return Charset.forName(charsetName);
    }

    public boolean isEbcdic() {
        return ebcdic;
    }
}
