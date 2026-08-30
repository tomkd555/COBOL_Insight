package jp.cobolinsight.analysis.linker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinkerModuleSmokeTest {

    private static final String MODULE_NAME = "linker";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.analysis.linker";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, LinkerModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
