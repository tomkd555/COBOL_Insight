package jp.cobolinsight.app.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliModuleSmokeTest {

    private static final String MODULE_NAME = "cli";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.app.cli";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, CliModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
