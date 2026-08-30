package jp.cobolinsight.frontend.bms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Confirms that, even for malformed and boundary BMS input, parsing never throws an exception and
 * instead collects errors into {@link BmsParseResult#errors()} (the contract of BmsSourceParser).
 * Also confirms that conversion into the engine-api model never ends in an exception, even for a
 * syntax tree containing an unrecognized macro.
 */
class BmsMalformedSourceTest {

    private static final List<String> MALFORMED = List.of(
            "",
            "   \n  \n",
            "!@#$%^&*() garbage tokens 123",
            "DFHMSD",
            "SYKMAP1  DFHMSD TYPE=MAP,MODE=INOUT",
            "SYKMAP1  DFHMSD TYPE=MAP\nSYKM01   DFHMDI SIZE=(24,80)\nORDNO    DFHMDF",
            "         DFHMDF POS=(1,1),LENGTH=8\n         DFHMDI\n",
            "SYKM01   DFHMDI SIZE=(,)\n         DFHMDF POS=(,),LENGTH=\n",
            "*コメントのみ\n* もう一行\n",
            "SYKMAP1  DFHMSD TYPE=MAP\n         END",
            "SYKMAP1 DFHMSD TYPE=&VAR,CTRL=(FREEKB,\n");

    @Test
    void parsingAndMappingNeverThrowOnMalformedInput() {
        BmsSourceParser parser = new BmsSourceParser();
        for (String source : MALFORMED) {
            BmsParseResult result = parser.parse(source);
            List<jp.cobolinsight.core.bms.BmsMapset> mapsets =
                    BmsModelMapper.toEngineApi(result, "bms/probe.bms");
            assertNotNull(mapsets, "変換は例外を投げず結果を返すこと");
        }
    }

    @Test
    void unrecognizedMacroIsReportedAsErrorNotException() {
        BmsParseResult result = new BmsSourceParser().parse("!@#$%^&*() garbage tokens 123");
        assertFalse(result.errors().isEmpty(),
                "認識できないマクロは例外でなく errors() へ集約すること");
    }

    @Test
    void errorRecoveryDoesNotFabricateMapsets() {
        // Input where DFHMSD is actually present is out of scope. What is under test here is the pseudo-token arising from a macro name absent from the original.
        for (String source : List.of(
                "!@#$%^&*() garbage tokens 123",
                "PART1    DFHPSD TYPE=INITIAL",
                "XINIT=1D")) {
            BmsParseResult result = new BmsSourceParser().parse(source);
            assertEquals(List.of(), BmsModelMapper.toEngineApi(result, "bms/probe.bms"),
                    () -> "エラー回復で補った擬似トークンからマップセットを作らないこと: " + source);
        }
    }
}
