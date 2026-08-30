package jp.cobolinsight.frontend.bms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BmsFrontendModuleSmokeTest {

    private static final String MODULE_NAME = "bms-frontend";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.frontend.bms";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, BmsFrontendModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
