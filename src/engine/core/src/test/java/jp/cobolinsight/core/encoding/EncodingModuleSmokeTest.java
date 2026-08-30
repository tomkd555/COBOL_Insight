package jp.cobolinsight.core.encoding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EncodingModuleSmokeTest {

    private static final String MODULE_NAME = "encoding";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.core.encoding";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, EncodingModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
