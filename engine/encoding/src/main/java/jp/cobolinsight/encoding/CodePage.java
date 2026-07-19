package jp.cobolinsight.encoding;

import java.nio.charset.Charset;

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
