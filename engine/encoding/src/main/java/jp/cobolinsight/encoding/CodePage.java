package jp.cobolinsight.encoding;

import java.nio.charset.Charset;
import java.util.Locale;

/** 本ツールが扱うコードページ。 */
public enum CodePage {
    UTF_8("UTF-8", false),
    SHIFT_JIS("windows-31j", false),
    IBM930("x-IBM930", true),
    IBM939("x-IBM939", true);

    private final String charsetName;
    private final boolean ebcdic;

    CodePage(String charsetName, boolean ebcdic) {
        this.charsetName = charsetName;
        this.ebcdic = ebcdic;
    }

    /**
     * コードページ名(別名を含む)を解決する。英数字以外を除いた大小無視の照合で、UTF-8・Shift_JIS・
     * IBM930・IBM939 の別名を受け付ける。未対応の名前は {@link IllegalArgumentException}。
     */
    public static CodePage fromName(String charsetName) {
        String key = charsetName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return switch (key) {
            case "utf8" -> UTF_8;
            case "shiftjis", "sjis", "windows31j", "ms932", "cp932", "932" -> SHIFT_JIS;
            case "ibm930", "xibm930", "cp930", "930" -> IBM930;
            case "ibm939", "xibm939", "cp939", "939" -> IBM939;
            default -> throw new IllegalArgumentException("未対応のコードページ指定: " + charsetName);
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
