package jp.cobolinsight.frontend.bms;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BmsSyntaxFixtureTest {

    /** 72桁目に継続指示子Xを置いた行を作る。 */
    private static String cont(String code) {
        return code + " ".repeat(71 - code.length()) + "X";
    }

    private static BmsParseResult parse(String... lines) {
        return new BmsSourceParser().parse(String.join("\n", lines) + "\n");
    }

    @Test
    void 継続行をまたいだパラメータ列を1文として解析する() {
        BmsParseResult result = parse(
                "MYSET    DFHMSD TYPE=MAP,MODE=INOUT,LANG=COBOL,TIOAPFX=YES",
                "MYMAP    DFHMDI SIZE=(24,80),LINE=1,COLUMN=1",
                cont("FLD1     DFHMDF POS=(1,2),"),
                cont("               LENGTH=10,"),
                "               ATTRB=(ASKIP,NORM)",
                "         DFHMSD TYPE=FINAL",
                "         END");

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        assertEquals(1, result.mapsets().size());
        BmsMap map = result.mapsets().get(0).maps().get(0);
        assertEquals(1, map.positionLine().intValue());
        assertEquals(1, map.positionColumn().intValue());
        assertEquals(1, map.fields().size());
        BmsField fld1 = map.fields().get(0);
        assertEquals("FLD1", fld1.name());
        assertEquals(3, fld1.sourceLine());
        assertEquals(1, fld1.posRow().intValue());
        assertEquals(2, fld1.posColumn().intValue());
        assertEquals(10, fld1.length().intValue());
        assertEquals(List.of("ASKIP", "NORM"), fld1.attributes());
    }

    @Test
    void コメント行を読み飛ばす() {
        BmsParseResult result = parse(
                "* マップセット定義のコメント",
                "MYSET    DFHMSD TYPE=MAP,MODE=INOUT",
                "* マップ定義のコメント",
                "MYMAP    DFHMDI SIZE=(24,80)",
                "FLD1     DFHMDF POS=(1,2),LENGTH=3",
                "         DFHMSD TYPE=FINAL",
                "         END");

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        assertEquals(1, result.mapsets().size());
        assertEquals(2, result.mapsets().get(0).sourceLine());
        assertEquals(1, result.mapsets().get(0).maps().get(0).fields().size());
    }

    @Test
    void ラベル無しDFHMDFを無名フィールドとして扱う() {
        BmsParseResult result = parse(
                "MYSET    DFHMSD TYPE=MAP",
                "MYMAP    DFHMDI SIZE=(24,80)",
                "         DFHMDF POS=(5,1),LENGTH=3,ATTRB=ASKIP,INITIAL='ABC'",
                "         DFHMSD TYPE=FINAL");

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        BmsField field = result.mapsets().get(0).maps().get(0).fields().get(0);
        assertNull(field.name());
        assertEquals(5, field.posRow().intValue());
        assertEquals(1, field.posColumn().intValue());
        assertEquals(3, field.length().intValue());
        assertEquals(List.of("ASKIP"), field.attributes());
    }

    @Test
    void 括弧付き複数値とシンボリックパラメータを受理する() {
        BmsParseResult result = parse(
                "MYSET    DFHMSD TYPE=&SYSPARM,CTRL=(FREEKB,FRSET),MODE=INOUT,STORAGE=AUTO",
                "MYMAP    DFHMDI SIZE=(24,80),CTRL=(FREEKB,ALARM)",
                "FLD1     DFHMDF POS=(1,2),LENGTH=5,ATTRB=(UNPROT,NUM,IC),INITIAL='IT''S'",
                "         DFHMSD TYPE=FINAL");

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        BmsField fld1 = result.mapsets().get(0).maps().get(0).fields().get(0);
        assertEquals(List.of("UNPROT", "NUM", "IC"), fld1.attributes());
    }

    @Test
    void TYPE_FINALのDFHMSDは新しいマップセットを作らない() {
        BmsParseResult result = parse(
                "SET1     DFHMSD TYPE=MAP",
                "MAP1     DFHMDI SIZE=(24,80)",
                "         DFHMSD TYPE=FINAL",
                "SET2     DFHMSD TYPE=MAP",
                "MAP2     DFHMDI SIZE=(12,40)",
                "         DFHMSD TYPE=FINAL",
                "         END");

        assertTrue(result.errors().isEmpty(), "エラーなし: " + result.errors());
        assertEquals(2, result.mapsets().size());
        assertEquals("SET1", result.mapsets().get(0).name());
        assertEquals("SET2", result.mapsets().get(1).name());
        assertEquals(List.of("MAP1"), result.mapsets().get(0).maps().stream().map(BmsMap::name).toList());
        assertEquals(List.of("MAP2"), result.mapsets().get(1).maps().stream().map(BmsMap::name).toList());
    }
}
