package jp.cobolinsight.sqlfrontend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlBlockExtractorTest {

    private final SqlBlockExtractor extractor = new SqlBlockExtractor();

    private static String readSample(String fileName) throws IOException {
        return Files.readString(
                Path.of("..", "..", "..", "samples", "cobol", fileName), StandardCharsets.UTF_8);
    }

    private static String normalized(SqlBlock block) {
        return block.sqlText().replaceAll("\\s+", " ").trim();
    }

    @Test
    void syk006の全ブロックを種別つきで抽出する() throws IOException {
        List<SqlBlock> blocks = extractor.extract(readSample("SYK006.cbl"));
        assertEquals(10, blocks.size());
        assertEquals(List.of(
                        SqlBlockKind.INCLUDE,
                        SqlBlockKind.BEGIN_DECLARE_SECTION,
                        SqlBlockKind.END_DECLARE_SECTION,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.DECLARE_CURSOR,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.EXECUTABLE),
                blocks.stream().map(SqlBlock::kind).toList());
    }

    @Test
    void syk006のincludeブロックの本文と位置を保持する() throws IOException {
        SqlBlock block = extractor.extract(readSample("SYK006.cbl")).get(0);
        assertEquals("INCLUDE SQLCA", block.sqlText());
        assertEquals(new SourcePosition(43, 12), block.start());
        assertEquals(new SourcePosition(43, 42), block.end());
    }

    @Test
    void syk006の複数行selectブロックの本文と位置を保持する() throws IOException {
        SqlBlock block = extractor.extract(readSample("SYK006.cbl")).get(3);
        assertEquals(new SourcePosition(96, 12), block.start());
        assertEquals(new SourcePosition(101, 19), block.end());
        assertEquals("SELECT ZAIKO_SU INTO :HOST-在庫数量"
                        + " FROM SYKDB.ZAIKOM"
                        + " WHERE SHOHIN_CD = :HOST-商品コード"
                        + " AND SOKO_CD = :HOST-倉庫コード",
                normalized(block));
    }

    @Test
    void syk007の全ブロックを種別つきで抽出する() throws IOException {
        List<SqlBlock> blocks = extractor.extract(readSample("SYK007.cbl"));
        assertEquals(5, blocks.size());
        assertEquals(List.of(
                        SqlBlockKind.INCLUDE,
                        SqlBlockKind.BEGIN_DECLARE_SECTION,
                        SqlBlockKind.END_DECLARE_SECTION,
                        SqlBlockKind.EXECUTABLE,
                        SqlBlockKind.EXECUTABLE),
                blocks.stream().map(SqlBlock::kind).toList());
        assertEquals("SELECT SOKO_NM INTO :HOST-倉庫名"
                        + " FROM SYKDB.SOKOM"
                        + " WHERE SOKO_CD = :HOST-倉庫コード",
                normalized(blocks.get(3)));
    }

    @Test
    void wheneverブロックを分類しコメント行は読み飛ばす() {
        String source =
                "      *    EXEC SQL WHENEVER SQLERROR CONTINUE END-EXEC\n"
                + "           EXEC SQL\n"
                + "               WHENEVER SQLERROR GO TO 9999-ERR\n"
                + "           END-EXEC.\n";
        List<SqlBlock> blocks = extractor.extract(source);
        assertEquals(1, blocks.size());
        assertEquals(SqlBlockKind.WHENEVER, blocks.get(0).kind());
        assertEquals("WHENEVER SQLERROR GO TO 9999-ERR", normalized(blocks.get(0)));
    }

    @Test
    void 一行で完結するブロックを抽出する() {
        List<SqlBlock> blocks = extractor.extract("           EXEC SQL COMMIT END-EXEC.\n");
        assertEquals(1, blocks.size());
        assertEquals(SqlBlockKind.EXECUTABLE, blocks.get(0).kind());
        assertEquals("COMMIT", blocks.get(0).sqlText());
    }
}
