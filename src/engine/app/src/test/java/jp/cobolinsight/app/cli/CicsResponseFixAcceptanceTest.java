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
 * R021 CICS応答コード未検査の修正受入。samples の唯一の検出(SYK008:38)に対し、fix が END-EXEC の
 * 直前へ RESP オペランド行を、直後へ応答コードの判定文を挿入し、挿入行が固定形式の桁規則を保ち、
 * 修正後ソースが再パースに成功することを検証する。原本 samples は読み込むだけで変更しない。
 *
 * <p>受け変数は WORKING-STORAGE 宣言済みで名前に RESP を含む PIC S9(08) COMP 相当の基本項目に
 * 限るため、SYK008 では 29 行の WS-RESPコード が選ばれる。
 */
class CicsResponseFixAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    /** 挿入文の B領域起点。一連番号欄(6桁)+標識欄(1桁)+A領域(4桁)=11桁の空白。 */
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
        // オペランド列の末尾(END-EXEC の直前)へ入ること。
        assertEquals("END-EXEC", lines.get(at + 1).strip(), "直後の行が END-EXEC であること");
        // 判定文は END-EXEC の直後へ続くこと。
        assertEquals(JUDGEMENT.get(0), lines.get(at + 2));
        assertEquals(JUDGEMENT.get(1), lines.get(at + 3));
        // 直前の行は元のオペランド INTO(...) であること。
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
