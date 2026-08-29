package jp.cobolinsight.frontend.jcl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JclFrontendModuleSmokeTest {

    private static final String MODULE_NAME = "jcl-frontend";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.frontend.jcl";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, JclFrontendModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
