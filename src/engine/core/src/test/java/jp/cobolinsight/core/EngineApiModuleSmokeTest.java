package jp.cobolinsight.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EngineApiModuleSmokeTest {

    private static final String MODULE_NAME = "engine-api";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.core";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, EngineApiModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
