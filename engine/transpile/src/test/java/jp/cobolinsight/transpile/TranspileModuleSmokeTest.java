package jp.cobolinsight.transpile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranspileModuleSmokeTest {

    private static final String MODULE_NAME = "transpile";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.transpile";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, TranspileModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
