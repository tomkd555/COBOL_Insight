package jp.cobolinsight.cobolfrontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CobolFrontendModuleSmokeTest {

    private static final String MODULE_NAME = "cobol-frontend";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.cobolfrontend";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, CobolFrontendModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
