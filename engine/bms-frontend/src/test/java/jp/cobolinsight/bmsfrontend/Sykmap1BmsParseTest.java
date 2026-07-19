package jp.cobolinsight.bmsfrontend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Sykmap1BmsParseTest {

    private static final Path SYKMAP1 = Path.of("..", "..", "samples", "bms", "SYKMAP1.bms");

    @Test
    void sykmap1の階層モデルが期待結果と一致する() throws IOException {
        assertTrue(Files.exists(SYKMAP1), "検証資産 " + SYKMAP1 + " が存在する");

        BmsParseResult result = new BmsSourceParser().parseFile(SYKMAP1);

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        assertEquals(1, result.mapsets().size());

        BmsMapset mapset = result.mapsets().get(0);
        assertEquals("SYKMAP1", mapset.name());
        assertEquals(7, mapset.sourceLine());
        assertEquals(1, mapset.maps().size());

        BmsMap map = mapset.maps().get(0);
        assertEquals("SYKM01", map.name());
        assertEquals(13, map.sourceLine());
        assertEquals(24, map.sizeRows().intValue());
        assertEquals(80, map.sizeColumns().intValue());
        assertEquals(2, map.fields().size());

        BmsField ordno = map.fields().get(0);
        assertEquals("ORDNO", ordno.name());
        assertEquals(15, ordno.sourceLine());
        assertEquals(3, ordno.posRow().intValue());
        assertEquals(10, ordno.posColumn().intValue());
        assertEquals(8, ordno.length().intValue());
        assertEquals(List.of("UNPROT", "NUM"), ordno.attributes());

        BmsField msg = map.fields().get(1);
        assertEquals("MSG", msg.name());
        assertEquals(19, msg.sourceLine());
        assertEquals(22, msg.posRow().intValue());
        assertEquals(5, msg.posColumn().intValue());
        assertEquals(40, msg.length().intValue());
        assertEquals(List.of("PROT", "BRT"), msg.attributes());
    }
}
