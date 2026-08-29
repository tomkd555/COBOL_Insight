package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** コードページ名の別名解決({@link CodePage#fromName})の検証。 */
class CodePageTest {

    @Test
    void resolvesUtf8Aliases() {
        assertEquals(CodePage.UTF_8, CodePage.fromName("UTF-8"));
        assertEquals(CodePage.UTF_8, CodePage.fromName("utf8"));
    }

    @Test
    void resolvesShiftJisAliases() {
        assertEquals(CodePage.SHIFT_JIS, CodePage.fromName("Shift_JIS"));
        assertEquals(CodePage.SHIFT_JIS, CodePage.fromName("sjis"));
        assertEquals(CodePage.SHIFT_JIS, CodePage.fromName("windows-31j"));
        assertEquals(CodePage.SHIFT_JIS, CodePage.fromName("cp932"));
        assertEquals(CodePage.SHIFT_JIS, CodePage.fromName("932"));
    }

    @Test
    void resolvesEbcdicAliases() {
        assertEquals(CodePage.IBM930, CodePage.fromName("x-IBM930"));
        assertEquals(CodePage.IBM930, CodePage.fromName("cp930"));
        assertEquals(CodePage.IBM939, CodePage.fromName("ibm939"));
    }

    @Test
    void rejectsUnknownCharsetName() {
        assertThrows(IllegalArgumentException.class, () -> CodePage.fromName("EUC-KR"));
    }
}
