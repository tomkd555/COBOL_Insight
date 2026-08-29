package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.fix.ReparseResult;
import jp.cobolinsight.core.fix.ReparseVerifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance regression test for fix generation. Runs fix (FixRunner=apply's core) against
 * samples and verifies, for each of the following 5 defects, that the expected handler is
 * inserted at the correct position, that the inserted physical lines preserve the fixed-format
 * column rules (sequence-number area 1-6, indicator area 7, area B 8-72, identification area
 * 73-80), and that the fixed source reparses successfully. The original samples are only read,
 * never modified.
 *
 * <p>Target 5 defects: No.14 R004 (SYK007 COMPUTE -> ON SIZE ERROR+END-COMPUTE), No.4 R017
 * (SYK001 READ ORDIN -> FILE STATUS check), No.6 R017 (SYK002 REWRITE -> FILE STATUS check),
 * No.12 R018 (SYK006 EXEC SQL UPDATE -> SQLCODE check), No.15 R018 (SYK007 EXEC SQL UPDATE
 * -> SQLCODE check).
 *
 * <p>Also covers the 4 R017 fixes attached to I/O statements in the middle of an enclosing
 * statement (IF/PERFORM) (SYK001:126, SYK001:130, SYK002:107, SYK006:172). Since these have no
 * terminating period, the inserted IF is closed with just END-IF and no period. Because R017
 * targets fix generation, I/O inside a block is also a target for the fix.
 */
class FixSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    /** Area B starting column of the inserted statement. The body starts after 11 blank columns
     * (sequence-number area 6 digits + indicator area 1 digit + area A 4 digits). */
    private static final String PAD = "           ";

    /** Expected insertion content and insertion position (condition on the preceding physical line) for each defect. */
    private record Defect(String no, String rule, String relPath, List<String> handlerLines,
            Predicate<String> precedingLine, String precedingDesc) {
    }

    private static final List<Defect> DEFECTS = List.of(
            new Defect("No.4", "R017", "cobol/SYK001.cbl",
                    List.of(PAD + "IF WS-ORDIN-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDIN '",
                            PAD + "WS-ORDIN-STATUS END-IF."),
                    prev -> prev.endsWith("END-READ."), "READ ORDIN の END-READ 直後"),
            new Defect("R017 THEN節", "R017", "cobol/SYK001.cbl",
                    List.of(PAD + "IF WS-ORDERR-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDERR '",
                            PAD + "WS-ORDERR-STATUS END-IF"),
                    prev -> prev.equals("WRITE ERROR-REC"), "IF の THEN 節末尾の WRITE 直後"),
            new Defect("R017 ELSE節", "R017", "cobol/SYK001.cbl",
                    List.of(PAD + "IF WS-ORDVALID-STATUS NOT = '00' DISPLAY",
                            PAD + "'FILE ERROR: ORDVALID ' WS-ORDVALID-STATUS END-IF"),
                    prev -> prev.equals("WRITE VALID-REC"), "IF の ELSE 節末尾の WRITE 直後"),
            new Defect("R017 END-WRITE", "R017", "cobol/SYK002.cbl",
                    List.of(PAD + "IF WS-MASTER-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDMSTR '",
                            PAD + "WS-MASTER-STATUS END-IF"),
                    prev -> prev.equals("END-WRITE"), "INVALID KEY 付き WRITE の END-WRITE 直後"),
            new Defect("R017 IF内", "R017", "cobol/SYK006.cbl",
                    List.of(PAD + "IF WS-STKEXTR-STATUS NOT = '00' DISPLAY",
                            PAD + "'FILE ERROR: STKEXTR ' WS-STKEXTR-STATUS END-IF"),
                    prev -> prev.equals("WRITE SYK3-在庫抽出レコード"), "IF の中の WRITE 直後"),
            new Defect("No.6", "R017", "cobol/SYK002.cbl",
                    List.of(PAD + "IF WS-MASTER-STATUS NOT = '00' DISPLAY 'FILE ERROR: ORDMSTR '",
                            PAD + "WS-MASTER-STATUS END-IF."),
                    prev -> prev.startsWith("REWRITE") && prev.endsWith("."), "REWRITE 文の直後"),
            new Defect("No.12", "R018", "cobol/SYK006.cbl",
                    List.of(PAD + "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF."),
                    prev -> prev.endsWith("END-EXEC."), "EXEC SQL UPDATE の END-EXEC 直後"),
            new Defect("No.15", "R018", "cobol/SYK007.cbl",
                    List.of(PAD + "IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF."),
                    prev -> prev.endsWith("END-EXEC."), "EXEC SQL UPDATE の END-EXEC 直後"),
            new Defect("No.14", "R004", "cobol/SYK007.cbl",
                    List.of(PAD + "ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率' END-COMPUTE."),
                    prev -> !prev.endsWith(".") && prev.contains("SYK3-在庫数量"),
                    "COMPUTE の内容終端(終止ピリオドは END-COMPUTE の後へ移る)"));

    private FixRunner.Result runFix() {
        List<Path> copybookPaths = CommonScanOptions.resolveCopybookPaths(SAMPLES, List.of());
        return new FixRunner().run(new FixRunner.Options(SAMPLES, copybookPaths, Map.of()));
    }

    private FixRunner.FileFix fixOf(FixRunner.Result result, String relPath) {
        return result.fileFixes().stream()
                .filter(fix -> fix.relPath().equals(relPath))
                .findFirst()
                .orElseThrow(() -> new AssertionError(relPath + " の修正結果が無い"));
    }

    @Test
    void eachDefectInsertsExpectedHandlerAtExpectedPosition() {
        FixRunner.Result result = runFix();
        assertEquals(0, result.analysisErrors(), "復号・パース失敗が無いこと");

        for (Defect defect : DEFECTS) {
            FixRunner.FileFix fix = fixOf(result, defect.relPath());
            List<String> lines = fix.fixedText().lines().toList();
            List<String> handler = defect.handlerLines();

            // All physical lines of the handler must appear consecutively. Handlers that differ
            // only in the presence of a period can appear side by side in the same file, so
            // match against the whole block rather than just the first line.
            int at = indexOfBlock(lines, handler);
            assertTrue(at >= 0, defect.no() + " " + defect.rule()
                    + ": 期待ハンドラが無い: " + handler + " in " + defect.relPath());
            // Insertion position: the preceding physical line must satisfy the expected insertion condition.
            assertTrue(at >= 1, defect.no() + ": ハンドラの直前行が存在すること");
            String preceding = lines.get(at - 1).strip();
            assertTrue(defect.precedingLine().test(preceding),
                    defect.no() + " " + defect.rule() + ": 挿入位置が「" + defect.precedingDesc()
                            + "」であること。直前行=[" + preceding + "]");

            assertColumnRules(defect, fix.charsetName(), handler);
        }
    }

    @Test
    void everyDetectionOfTheFourFixableRulesYieldsEdits() {
        // Of the 4 rules that generate fixes (R004, R017, R018, R021), the samples detections
        // are: R004 1, R017 9, R018 2, R021 1. R021 edits two spots (the RESP operand and the
        // check statement), so the total edit count is 1+9+2+2=14.
        FixRunner.Result result = runFix();
        assertEquals(14, result.fixCount(), "編集の総数");
    }

    @Test
    void fixedSourcesReparseSuccessfully() {
        FixRunner.Result result = runFix();
        ReparseVerifier verifier = EngineWiring.reparseVerifier();
        List<Path> copybookPaths = CommonScanOptions.resolveCopybookPaths(SAMPLES, List.of());

        assertTrue(result.fileFixes().size() >= 4, "少なくとも SYK001/002/006/007 が修正されること");
        for (FixRunner.FileFix fix : result.fileFixes()) {
            ReparseResult reparse =
                    verifier.verify(fix.relPath(), fix.fixedBytes(), fix.charsetName(), copybookPaths);
            assertTrue(reparse.errorFinding().isEmpty(),
                    fix.relPath() + " の修正後ソースが再パースに成功すること: "
                            + reparse.errorFinding().map(f -> f.message()).orElse(""));
        }
    }

    @Test
    void computeReceivingLineLosesTerminatorSoPeriodMovesAfterEndCompute() {
        // No.14 inserts ON SIZE ERROR+END-COMPUTE at the end of the COMPUTE statement body and
        // moves the terminating period to after END-COMPUTE. The period at the end of the
        // original receiving line disappears, and the next line closes with END-COMPUTE.
        FixRunner.FileFix fix = fixOf(runFix(), "cobol/SYK007.cbl");
        assertTrue(fix.fixedText().contains(
                        "/ SYK3-在庫数量\n" + PAD + "ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率' END-COMPUTE."),
                "COMPUTE 受信行はピリオド無しで閉じ、直後に END-COMPUTE. の行が続くこと");
        assertTrue(!fix.fixedText().contains("/ SYK3-在庫数量."),
                "元の受信行末の終止ピリオドが END-COMPUTE の後へ移ること");
    }

    @Test
    void originalSamplesAreNotModified() throws IOException {
        Map<String, byte[]> before = snapshot();
        runFix();
        for (Map.Entry<String, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(SAMPLES.resolve(entry.getKey())),
                    entry.getKey() + " (原本)は変更されないこと");
        }
    }

    private Map<String, byte[]> snapshot() throws IOException {
        return Map.of(
                "cobol/SYK001.cbl", Files.readAllBytes(SAMPLES.resolve("cobol/SYK001.cbl")),
                "cobol/SYK002.cbl", Files.readAllBytes(SAMPLES.resolve("cobol/SYK002.cbl")),
                "cobol/SYK006.cbl", Files.readAllBytes(SAMPLES.resolve("cobol/SYK006.cbl")),
                "cobol/SYK007.cbl", Files.readAllBytes(SAMPLES.resolve("cobol/SYK007.cbl")),
                "cobol/SYK008.cbl", Files.readAllBytes(SAMPLES.resolve("cobol/SYK008.cbl")));
    }

    /** Asserts that each inserted physical line preserves the fixed-format column rules. Columns are counted in bytes of the file's encoding. */
    private void assertColumnRules(Defect defect, String charsetName, List<String> handlerLines) {
        Charset charset = Charset.forName(charsetName);
        for (String line : handlerLines) {
            byte[] bytes = line.getBytes(charset);
            // Must not break into the identification area (columns 73-80) = the body must fit
            // within the end of area B (column 72).
            assertTrue(bytes.length <= 72,
                    defect.no() + ": 本文が72桁以内であること(識別欄不可侵)。len=" + bytes.length
                            + " [" + line + "]");
            // The sequence-number area (columns 1-6) is blank (inserted lines are new lines, so always blank).
            for (int i = 0; i < 6; i++) {
                assertEquals((byte) ' ', bytes[i],
                        defect.no() + ": 一連番号欄(1-6桁)は空白であること [" + line + "]");
            }
            // The indicator area (column 7) is blank. Word-boundary wrapping does not use the '-' continuation mark.
            assertEquals((byte) ' ', bytes[6],
                    defect.no() + ": 標識欄(7桁)は空白であること(語境界折り返しは '-' を使わない) ["
                            + line + "]");
            // The body starts at the area B origin = column 12.
            assertEquals(11, firstNonSpace(line),
                    defect.no() + ": 本文はB領域起点(12桁目)から始まること [" + line + "]");
        }
    }

    private static int firstNonSpace(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != ' ') {
                return i;
            }
        }
        return -1;
    }

    /** The 0-based starting position where consecutive physical lines exactly match block. -1 if not found. */
    private static int indexOfBlock(List<String> lines, List<String> block) {
        for (int i = 0; i + block.size() <= lines.size(); i++) {
            if (lines.subList(i, i + block.size()).equals(block)) {
                return i;
            }
        }
        return -1;
    }
}
