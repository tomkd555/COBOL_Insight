package jp.cobolinsight.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Confirms that the rules module's test sources are placed in the package the convention requires. */
class RulesModuleSmokeTest {

    private static final String MODULE_NAME = "rules";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.rules";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, RulesModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
