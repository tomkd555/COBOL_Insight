package jp.cobolinsight.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** rules モジュールのテストソースが規約どおりのパッケージに置かれていることの確認。 */
class RulesModuleSmokeTest {

    private static final String MODULE_NAME = "rules";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.rules";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, RulesModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
