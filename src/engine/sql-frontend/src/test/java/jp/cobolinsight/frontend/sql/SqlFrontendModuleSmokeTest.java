package jp.cobolinsight.frontend.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlFrontendModuleSmokeTest {

    private static final String MODULE_NAME = "sql-frontend";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.frontend.sql";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, SqlFrontendModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
