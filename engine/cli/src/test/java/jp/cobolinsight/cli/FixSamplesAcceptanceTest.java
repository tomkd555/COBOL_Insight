package jp.cobolinsight.cli;

import jp.cobolinsight.fix.ReparseResult;
import jp.cobolinsight.fix.ReparseVerifier;
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
 * 修正案生成の受入回帰テスト。samples を対象に fix(FixRunner=apply の中核)を実行し、次の
 * 5欠陥それぞれについて、期待するハンドラが該当位置へ挿入されること・挿入した物理行が固定形式の
 * 桁規則(一連番号欄1-6・標識欄7・B領域8-72・識別欄73-80)を保つこと・修正後ソースが再パースに
 * 成功することを検証する。原本 samples は読み込むだけで変更しない。
 *
 * <p>対象5欠陥: No.14 R004(SYK007 COMPUTE→ON SIZE ERROR+END-COMPUTE)、No.4 R017(SYK001
 * READ ORDIN→FILE STATUS 検査)、No.6 R017(SYK002 REWRITE→FILE STATUS 検査)、No.12 R018
 * (SYK006 EXEC SQL UPDATE→SQLCODE 検査)、No.15 R018(SYK007 EXEC SQL UPDATE→SQLCODE 検査)。
 *
 * <p>あわせて、囲む文(IF/PERFORM)の途中にある I/O へ付く R017 の修正案4件(SYK001:126・
 * SYK001:130・SYK002:107・SYK006:172)も対象とする。これらは終止ピリオドを持たないため、挿入する
 * IF もピリオドを付けず END-IF だけで閉じる。R017 は修正案生成の対象であるため、ブロック内の
 * I/O も修正案の対象である。
 */
class FixSamplesAcceptanceTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    /** 挿入文の B領域起点。一連番号欄(6桁)+標識欄(1桁)+A領域(4桁)=11桁の空白の後に本文が始まる。 */
    private static final String PAD = "           ";

    /** 各欠陥の期待挿入内容と挿入位置(直前物理行の条件)。 */
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

            // ハンドラの全物理行が連続して現れること。ピリオドの有無だけが異なるハンドラが同一
            // ファイル内に並ぶため、先頭行ではなくブロック全体で照合する。
            int at = indexOfBlock(lines, handler);
            assertTrue(at >= 0, defect.no() + " " + defect.rule()
                    + ": 期待ハンドラが無い: " + handler + " in " + defect.relPath());
            // 挿入位置: 直前の物理行が期待する挿入位置の条件を満たすこと。
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
        // 修正案生成を持つ4ルール(R004・R017・R018・R021)の samples 検出は、R004 が1件・R017 が
        // 9件・R018 が2件・R021 が1件である。R021 は RESP オペランドと判定文の2か所を編集するため、
        // 編集の総数は 1+9+2+2=14 になる。
        FixRunner.Result result = runFix();
        assertEquals(14, result.fixCount(), "編集の総数");
    }

    @Test
    void fixedSourcesReparseSuccessfully() {
        FixRunner.Result result = runFix();
        ReparseVerifier verifier = new ReparseVerifier();
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
        // No.14 は COMPUTE の内容終端へ ON SIZE ERROR+END-COMPUTE を差し込み、終止ピリオドを
        // END-COMPUTE の後へ回す。元の受信行末のピリオドが消え、次行が END-COMPUTE. で閉じること。
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

    /** 挿入した各物理行が固定形式の桁規則を保つことを表明する。桁はファイル符号のバイト単位で数える。 */
    private void assertColumnRules(Defect defect, String charsetName, List<String> handlerLines) {
        Charset charset = Charset.forName(charsetName);
        for (String line : handlerLines) {
            byte[] bytes = line.getBytes(charset);
            // 識別欄(73-80桁)を割らない = 本文はB領域終端(72桁)以内に収まる。
            assertTrue(bytes.length <= 72,
                    defect.no() + ": 本文が72桁以内であること(識別欄不可侵)。len=" + bytes.length
                            + " [" + line + "]");
            // 一連番号欄(1-6桁)は空白(挿入行は新規行のため必ず空白)。
            for (int i = 0; i < 6; i++) {
                assertEquals((byte) ' ', bytes[i],
                        defect.no() + ": 一連番号欄(1-6桁)は空白であること [" + line + "]");
            }
            // 標識欄(7桁)は空白。語境界の折り返しは継続印 '-' を用いない。
            assertEquals((byte) ' ', bytes[6],
                    defect.no() + ": 標識欄(7桁)は空白であること(語境界折り返しは '-' を使わない) ["
                            + line + "]");
            // 本文はB領域起点=12桁目から始まる。
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

    /** 連続する物理行が block と完全一致する先頭位置(0始まり)。見つからなければ -1。 */
    private static int indexOfBlock(List<String> lines, List<String> block) {
        for (int i = 0; i + block.size() <= lines.size(); i++) {
            if (lines.subList(i, i + block.size()).equals(block)) {
                return i;
            }
        }
        return -1;
    }
}
