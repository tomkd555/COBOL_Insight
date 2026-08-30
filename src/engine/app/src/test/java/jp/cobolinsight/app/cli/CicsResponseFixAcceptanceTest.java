package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.fix.ReparseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance test for the R021 (unchecked CICS response code) fix. For the samples' single
 * detection (SYK008:38), verifies that fix inserts a RESP operand line immediately before
 * END-EXEC and a response-code check statement immediately after it, that the inserted lines
 * preserve the fixed-format column rules, and that the fixed source reparses successfully. The
 * original samples are only read, never modified.
 *
 * <p>Since the receiving variable is limited to an elementary item already declared in
 * WORKING-STORAGE, whose name contains RESP, and that is equivalent to PIC S9(08) COMP, SYK008
 * selects line 29's WS-RESPコード.
 */
class CicsResponseFixAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    /** Area B starting column of the inserted statement: 11 blank columns = sequence-number area (6 digits) + indicator area (1 digit) + area A (4 digits). */
    private static final String PAD = "           ";

    private static final String OPERAND = PAD + "RESP(WS-RESPコード)";
    private static final List<String> JUDGEMENT = List.of(
            PAD + "IF WS-RESPコード NOT = 0 DISPLAY",
            PAD + "'SYK008 RECEIVE MAPエラー RESP=' WS-RESPコード END-IF");

    private FixRunner.FileFix syk008Fix() {
        List<Path> copybookPaths = CommonScanOptions.resolveCopybookPaths(SAMPLES, List.of());
        FixRunner.Result result =
                new FixRunner().run(new FixRunner.Options(SAMPLES, copybookPaths, Map.of()));
        assertEquals(0, result.analysisErrors(), "復号・パース失敗が無いこと");
        return result.fileFixes().stream()
                .filter(fix -> fix.relPath().equals("cobol/SYK008.cbl"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SYK008 の修正結果が無い"));
    }

    @Test
    void insertsRespOperandBeforeEndExecAndJudgementAfterIt() {
        FixRunner.FileFix fix = syk008Fix();
        List<String> lines = fix.fixedText().lines().toList();

        int at = lines.indexOf(OPERAND);
        assertTrue(at >= 0, "RESP オペランド行が無い: [" + OPERAND + "]");
        // Must land at the end of the operand list (immediately before END-EXEC).
        assertEquals("END-EXEC", lines.get(at + 1).strip(), "直後の行が END-EXEC であること");
        // The check statement must continue immediately after END-EXEC.
        assertEquals(JUDGEMENT.get(0), lines.get(at + 2));
        assertEquals(JUDGEMENT.get(1), lines.get(at + 3));
        // The preceding line must be the original INTO(...) operand.
        assertEquals("INTO(WS-受注入力マップ)", lines.get(at - 1).strip());

        Charset charset = Charset.forName(fix.charsetName());
        for (String line : List.of(OPERAND, JUDGEMENT.get(0), JUDGEMENT.get(1))) {
            byte[] bytes = line.getBytes(charset);
            assertTrue(bytes.length <= 72,
                    "本文が72桁以内であること(識別欄不可侵)。len=" + bytes.length + " [" + line + "]");
            for (int i = 0; i < 6; i++) {
                assertEquals((byte) ' ', bytes[i], "一連番号欄(1-6桁)は空白であること [" + line + "]");
            }
            assertEquals((byte) ' ', bytes[6], "標識欄(7桁)は空白であること [" + line + "]");
            assertEquals(PAD, line.substring(0, 11), "本文はB領域起点(12桁目)から始まること [" + line + "]");
        }
    }

    @Test
    void fixedSyk008ReparsesSuccessfully() {
        FixRunner.FileFix fix = syk008Fix();
        List<Path> copybookPaths = CommonScanOptions.resolveCopybookPaths(SAMPLES, List.of());
        ReparseResult reparse = EngineWiring.reparseVerifier()
                .verify(fix.relPath(), fix.fixedBytes(), fix.charsetName(), copybookPaths);
        assertTrue(reparse.errorFinding().isEmpty(),
                "R021 修正後ソースが再パースに成功すること: "
                        + reparse.errorFinding().map(f -> f.message()).orElse(""));
    }
}
