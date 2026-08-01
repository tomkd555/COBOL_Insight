package jp.cobolinsight.bmsfrontend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** engine-api の BMSマップモデルへの変換の検証。 */
class BmsModelMapperTest {

    private static final Path SYKMAP1 = Path.of("..", "..", "..", "samples", "bms", "SYKMAP1.bms");

    @Test
    void sykmap1IsMappedToEngineApiModel() throws IOException {
        BmsParseResult result = new BmsSourceParser().parseFile(SYKMAP1);
        assertTrue(result.errors().isEmpty(), () -> "解析エラー: " + result.errors());

        List<jp.cobolinsight.engineapi.bms.BmsMapset> mapsets =
                BmsModelMapper.toEngineApi(result, "SYKMAP1.bms");

        assertEquals(1, mapsets.size());
        var mapset = mapsets.get(0);
        assertEquals("SYKMAP1", mapset.name());
        assertEquals("SYKMAP1.bms", mapset.sourceFile());

        var map = mapset.map("SYKM01").orElseThrow();
        assertEquals(24, map.sizeRows());
        assertEquals(80, map.sizeCols());
        assertEquals(2, map.fields().size());

        var ordno = map.field("ORDNO").orElseThrow();
        assertEquals(3, ordno.row());
        assertEquals(10, ordno.column());
        assertEquals(8, ordno.length());
        assertEquals("UNPROT,NUM", ordno.attributes());

        var msg = map.field("MSG").orElseThrow();
        assertEquals(22, msg.row());
        assertEquals(5, msg.column());
        assertEquals(40, msg.length());
        assertEquals("PROT,BRT", msg.attributes());
    }

    @Test
    void missingOptionalValuesFallBackToDefaults() {
        BmsParseResult result = new BmsSourceParser().parse("""
                MINI     DFHMSD TYPE=MAP
                M1       DFHMDI
                F1       DFHMDF POS=(1,1)
                         DFHMSD TYPE=FINAL
                         END
                """);

        var mapsets = BmsModelMapper.toEngineApi(result, "mini.bms");

        var map = mapsets.get(0).map("M1").orElseThrow();
        assertEquals(24, map.sizeRows());
        assertEquals(80, map.sizeCols());
        var field = map.field("F1").orElseThrow();
        assertEquals(0, field.length());
        assertEquals("", field.attributes());
    }
}
