package jp.cobolinsight.dataflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataflowModuleSmokeTest {

    private static final String MODULE_NAME = "dataflow";
    private static final String EXPECTED_PACKAGE = "jp.cobolinsight.dataflow";

    @Test
    void modulePackageMatchesModuleName() {
        assertEquals(EXPECTED_PACKAGE, DataflowModuleSmokeTest.class.getPackage().getName(),
                "module " + MODULE_NAME + " のテストクラスは " + EXPECTED_PACKAGE + " パッケージに置く");
    }
}
