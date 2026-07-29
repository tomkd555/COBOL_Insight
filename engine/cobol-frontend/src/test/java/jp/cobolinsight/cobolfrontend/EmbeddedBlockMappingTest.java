package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EXEC SQL / EXEC CICS の抽出(件数・種別・位置・オペランド)の検証。 */
class EmbeddedBlockMappingTest {

    private static List<EmbeddedBlock> blocksOf(String fileName, EmbeddedBlockKind kind) {
        return TestSources.model(fileName).embeddedBlocks().stream()
                .filter(b -> b.kind() == kind).toList();
    }

    private static EmbeddedBlock blockContaining(String fileName, String keyword) {
        return blocksOf(fileName, EmbeddedBlockKind.SQL).stream()
                .filter(b -> b.text().contains(keyword)).findFirst()
                .orElseThrow(() -> new AssertionError(
                        fileName + " に " + keyword + " を含む SQL ブロックが無い"));
    }

    @Test
    void syk006HasSevenSqlBlocksInProcedureDivision() {
        List<EmbeddedBlock> procedureBlocks = blocksOf("SYK006.cbl", EmbeddedBlockKind.SQL)
                .stream().filter(b -> b.range().start().line() >= 74).toList();
        assertEquals(7, procedureBlocks.size(),
                () -> "手続き部の SQL ブロック: " + procedureBlocks);
    }

    @Test
    void syk006CursorBlocksAreExtractedWithPositions() {
        EmbeddedBlock declare = blockContaining("SYK006.cbl", "DECLARE SYKZAIKOCUR CURSOR");
        assertEquals(145, declare.range().start().line());
        assertTrue(declare.range().start().file().endsWith("SYK006.cbl"));

        EmbeddedBlock open = blockContaining("SYK006.cbl", "OPEN SYKZAIKOCUR");
        assertEquals(150, open.range().start().line());
        EmbeddedBlock fetch = blockContaining("SYK006.cbl", "FETCH SYKZAIKOCUR");
        assertEquals(160, fetch.range().start().line());
        EmbeddedBlock close = blockContaining("SYK006.cbl", "CLOSE SYKZAIKOCUR");
        assertEquals(155, close.range().start().line());
    }

    @Test
    void syk006DmlBlocksAreExtracted() {
        assertEquals(96, blockContaining("SYK006.cbl", "SELECT ZAIKO_SU").range().start().line());
        assertEquals(114, blockContaining("SYK006.cbl", "UPDATE SYKDB.ZAIKOM").range().start().line());
        assertEquals(123, blockContaining("SYK006.cbl", "INSERT INTO SYKDB.ZAIKOM").range().start().line());
    }

    @Test
    void syk007SqlBlocksAreExtracted() {
        assertEquals(69, blockContaining("SYK007.cbl", "SELECT SOKO_NM").range().start().line());
        assertEquals(84, blockContaining("SYK007.cbl", "UPDATE SYKDB.ZAIKOM").range().start().line());
    }

    @Test
    void syk008ReceiveMap() {
        List<EmbeddedBlock> blocks = blocksOf("SYK008.cbl", EmbeddedBlockKind.CICS_RECEIVE_MAP);
        assertEquals(1, blocks.size());
        EmbeddedBlock block = blocks.get(0);
        assertEquals("SYKM01", block.operands().get("MAP"));
        assertEquals("SYKMAP1", block.operands().get("MAPSET"));
        assertEquals(34, block.range().start().line());
        assertEquals(38, block.range().end().line());
    }

    @Test
    void syk008SendMap() {
        List<EmbeddedBlock> blocks = blocksOf("SYK008.cbl", EmbeddedBlockKind.CICS_SEND_MAP);
        assertEquals(1, blocks.size());
        EmbeddedBlock block = blocks.get(0);
        assertEquals("SYKM99", block.operands().get("MAP"));
        assertEquals("SYKMAP1", block.operands().get("MAPSET"));
        assertEquals(55, block.range().start().line());
    }

    @Test
    void syk008Xctl() {
        List<EmbeddedBlock> blocks = blocksOf("SYK008.cbl", EmbeddedBlockKind.CICS_XCTL);
        assertEquals(1, blocks.size());
        EmbeddedBlock block = blocks.get(0);
        assertEquals("SYK009", block.operands().get("PROGRAM"));
        assertEquals(75, block.range().start().line());
    }

    @Test
    void syk008ReturnTransid() {
        List<EmbeddedBlock> blocks = blocksOf("SYK008.cbl", EmbeddedBlockKind.CICS_RETURN_TRANSID);
        assertEquals(1, blocks.size());
        EmbeddedBlock block = blocks.get(0);
        assertEquals("SYK8", block.operands().get("TRANSID"));
        assertEquals(65, block.range().start().line());
    }

    @Test
    void syk009HasNoEmbeddedBlocks() {
        assertTrue(TestSources.model("SYK009.cbl").embeddedBlocks().isEmpty());
    }

    @Test
    void linkAndStartAreExtracted(@TempDir Path tempDir) throws Exception {
        String fixture = """
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID.  CICSFX1.
                       DATA DIVISION.
                       WORKING-STORAGE SECTION.
                       01  WS-COMMAREA                     PIC X(10).
                       PROCEDURE DIVISION.
                       0000-MAIN.
                           EXEC CICS
                               LINK PROGRAM('SYKLNK1')
                                   COMMAREA(WS-COMMAREA)
                           END-EXEC
                           EXEC CICS
                               START TRANSID('SYK9')
                           END-EXEC
                           GOBACK.
                """;
        Path file = tempDir.resolve("CICSFX1.cbl");
        Files.writeString(file, fixture);
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                .parse(TestSources.load(file), TestSources.COPYBOOK_PATHS);
        CobolSemanticModel model = outcome.value().orElseThrow(
                () -> new AssertionError("失敗: " + outcome.failureFinding().orElse(null)));

        EmbeddedBlock link = model.embeddedBlocks().stream()
                .filter(b -> b.kind() == EmbeddedBlockKind.CICS_LINK).findFirst().orElseThrow();
        assertEquals("SYKLNK1", link.operands().get("PROGRAM"));
        EmbeddedBlock start = model.embeddedBlocks().stream()
                .filter(b -> b.kind() == EmbeddedBlockKind.CICS_START).findFirst().orElseThrow();
        assertEquals("SYK9", start.operands().get("TRANSID"));
    }
}
