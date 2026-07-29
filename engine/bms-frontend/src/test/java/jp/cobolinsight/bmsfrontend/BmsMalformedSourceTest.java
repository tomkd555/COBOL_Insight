package jp.cobolinsight.bmsfrontend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 不正な BMS 入力と境界の入力に対しても、解析は例外を投げず {@link BmsParseResult#errors()} へ
 * エラーを集約する（BmsSourceParser の契約）。認識できないマクロを含む構文木でも、
 * engine-api のモデルへの変換が例外で終わらないことを確かめる。
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
            List<jp.cobolinsight.engineapi.bms.BmsMapset> mapsets =
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
        // DFHMSD が実在する入力は対象外。ここで問うのは、原本に無いマクロ名から生じる擬似トークンである。
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
