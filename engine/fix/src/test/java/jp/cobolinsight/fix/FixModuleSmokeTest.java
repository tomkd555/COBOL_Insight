package jp.cobolinsight.fix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixModuleSmokeTest {

    private static final String MODULE_NAME = "fix";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.fix";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, FixModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
