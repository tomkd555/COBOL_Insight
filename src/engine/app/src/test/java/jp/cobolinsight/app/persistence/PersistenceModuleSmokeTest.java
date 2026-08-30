package jp.cobolinsight.app.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceModuleSmokeTest {

    private static final String MODULE_NAME = "persistence";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.app.persistence";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, PersistenceModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
