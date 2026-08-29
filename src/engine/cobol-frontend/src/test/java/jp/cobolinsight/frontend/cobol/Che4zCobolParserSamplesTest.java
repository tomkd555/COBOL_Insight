package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.ParseOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** samples/cobol 9本の全件パースと、SPI 登録・決定論の検証。 */
class Che4zCobolParserSamplesTest {

    @ParameterizedTest
    @CsvSource({
            "SYK001.cbl,SYK001",
            "SYK002.cbl,SYK002",
            "SYK003.cbl,SYK003",
            "SYK004.cbl,SYK004",
            "SYK005.cbl,SYK005",
            "SYK006.cbl,SYK006",
            "SYK007.cbl,SYK007",
            "SYK008.cbl,SYK008",
            "SYK009.cbl,SYK009",
    })
    void allSamplesParseSuccessfully(String fileName, String programId) {
        ParseOutcome<CobolSemanticModel> outcome = TestSources.parseSample(fileName);
        assertTrue(outcome.isSuccess(),
                () -> fileName + " が失敗した: " + outcome.failureFinding().orElse(null));
        CobolSemanticModel model = outcome.value().orElseThrow();
        assertEquals(programId, model.programId());
        assertTrue(model.sourceFile().endsWith(fileName));
    }

    @Test
    void sameInputYieldsSameModel() {
        Che4zCobolParser parser = new Che4zCobolParser();
        CobolSemanticModel first = parser
                .parse(TestSources.load(TestSources.COBOL_DIR.resolve("SYK001.cbl")),
                        TestSources.COPYBOOK_PATHS)
                .value().orElseThrow();
        CobolSemanticModel second = parser
                .parse(TestSources.load(TestSources.COBOL_DIR.resolve("SYK001.cbl")),
                        TestSources.COPYBOOK_PATHS)
                .value().orElseThrow();
        assertEquals(first, second);
    }

    @Test
    void brokenSourceYieldsParseFailure() {
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser().parse(
                TestSources.fromText("BROKEN.cbl",
                        "       IDENTIFICATION DIVISION.\n"
                                + "       PROGRAM-ID.  BROKEN.\n"
                                + "       PROCEDURE DIVISION.\n"
                                + "       0000-MAIN.\n"
                                + "           MOVE TO .\n"),
                TestSources.COPYBOOK_PATHS);
        assertTrue(!outcome.isSuccess(), "構文誤りは ParseOutcome.Failure になること");
        assertEquals("parse-failure", outcome.failureFinding().orElseThrow().ruleId());
    }
}
