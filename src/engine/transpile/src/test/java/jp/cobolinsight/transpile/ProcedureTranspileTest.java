package jp.cobolinsight.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
import jp.cobolinsight.transpile.emit.Transpiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Translates the procedure division of the samples with no GO TO (SYK001/003/004/005/007) verbatim into
 * Python/Java, and verifies determinism, the translation of the main statements (MOVE/COMPUTE/IF/
 * EVALUATE/PERFORM/CALL), whether the generated Java compiles, and the notes carried in the line map.
 */
class ProcedureTranspileTest {

    private static final List<String> SAMPLES =
            List.of("SYK001.cbl", "SYK003.cbl", "SYK004.cbl", "SYK005.cbl", "SYK007.cbl");

    private static TranspileResult transpile(String sample, TargetLanguage language) {
        return Transpiler.transpile(SampleModels.model(sample), SampleModels.sourceText(sample),
                language);
    }

    private static String program(TranspileResult result, String suffix) {
        return result.files().stream().filter(f -> f.fileName().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("プログラムファイルが無い: " + suffix)).content();
    }

    @Test
    void rerunIsByteIdentical() {
        for (String sample : SAMPLES) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult first = transpile(sample, language);
                TranspileResult second = transpile(sample, language);
                assertEquals(first.files().size(), second.files().size(), sample + " 生成ファイル数");
                for (int i = 0; i < first.files().size(); i++) {
                    assertEquals(first.files().get(i).content(), second.files().get(i).content(),
                            sample + " / " + language + " の再生成が一致");
                }
                assertEquals(first.lineMap(), second.lineMap(), sample + " の行対応が一致");
            }
        }
    }

    @Test
    void everySampleGeneratesAProgramFile() {
        for (String sample : SAMPLES) {
            assertTrue(program(transpile(sample, TargetLanguage.PYTHON), "_program.py").length() > 0);
            assertTrue(program(transpile(sample, TargetLanguage.JAVA), "Program.java").length() > 0);
        }
    }

    @Test
    void moveAndComputeAndConditions() {
        String syk003 = program(transpile("SYK003.cbl", TargetLanguage.PYTHON), "_program.py");
        assertTrue(syk003.contains("self.WS_合計 = 0"), syk003);
        assertTrue(syk003.contains("while not (self.WS_I > self.SYK1_明細件数):"), syk003);
        assertTrue(syk003.contains("self.WS_合計 = self.WS_合計 + self.SYK1_金額[self.WS_I - 1]"), syk003);
        assertTrue(syk003.contains("if self.WS_合計 == self.SYK1_受注金額合計:"), syk003);

        String syk007 = program(transpile("SYK007.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(syk007.contains(
                "WS_引当率 = (SYK3_引当可能数量 * 100) / SYK3_在庫数量;"), syk007);
    }

    @Test
    void inlinePerformVaryingRecoversLoopVariable() {
        String java = program(transpile("SYK001.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains("WS_IDX = 1;"), java);
        assertTrue(java.contains("while (!(WS_IDX > ORD1_明細件数)) {"), java);
        assertTrue(java.contains(
                "WS_合計チェック = WS_合計チェック + ORD1_金額[(int) (WS_IDX - 1)];"), java);
        assertTrue(java.contains("WS_IDX = WS_IDX + 1;"), java);
    }

    @Test
    void evaluateBecomesIfElifElse() {
        String py = program(transpile("SYK005.cbl", TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("if self.LK_メッセージ区分 == \"E1\":"), py);
        assertTrue(py.contains("elif self.LK_メッセージ区分 == \"W1\":"), py);
        assertTrue(py.contains("else:"), py);

        String syk001 = program(transpile("SYK001.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(syk001.contains("if (ORD1_処理区分.equals(\"1\")) {"), syk001);
        assertTrue(syk001.contains("} else if (ORD1_処理区分.equals(\"9\")) {"), syk001);
    }

    @Test
    void performCallAndCallProgram() {
        String java = program(transpile("SYK004.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains("_1000_在庫確認();"), java);
        assertTrue(java.contains("_2000_引当判定();"), java);

        String syk001 = program(transpile("SYK001.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(syk001.contains(
                "call_program(\"SYK003\", \"ORD1-受注レコード\", \"WS-チェック結果\");"), syk001);
    }

    @Test
    void execSqlAndFileIoBecomeAnnotatedComments() {
        String syk007 = program(transpile("SYK007.cbl", TargetLanguage.PYTHON), "_program.py");
        assertTrue(syk007.contains("# [直訳不能: EXEC SQL"), syk007);
        assertTrue(syk007.contains("# [直訳不能: ファイル I/O"), syk007);
    }

    @Test
    void lineMapCarriesProcedureNotesAndAnchors() {
        TranspileResult result = transpile("SYK007.cbl", TargetLanguage.PYTHON);
        List<LineMappingEntry> map = result.lineMap();
        boolean sawExecNote = false;
        int previousStart = 0;
        for (LineMappingEntry entry : map) {
            assertTrue(entry.anchorId().startsWith("SYK007#"), entry.anchorId());
            assertTrue(entry.cobolLines().startLine() >= previousStart, "COBOL開始行の昇順整列");
            previousStart = entry.cobolLines().startLine();
            if (entry.note().contains("EXEC SQL")) {
                sawExecNote = true;
            }
        }
        assertTrue(sawExecNote, "EXEC SQL の注記が行対応に載る");
    }

    @Test
    void generatedProgramJavaCompiles(@TempDir Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path out = Files.createDirectories(tempDir.resolve("out"));
        List<String> sourcePaths = new ArrayList<>();
        for (String sample : SAMPLES) {
            TranspileResult java = transpile(sample, TargetLanguage.JAVA);
            for (GeneratedFile file : java.files()) {
                if (!file.fileName().endsWith("Program.java")) {
                    continue;
                }
                Path path = src.resolve(file.fileName());
                Files.writeString(path, file.content(), StandardCharsets.UTF_8);
                sourcePaths.add(path.toString());
            }
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", out.toString()));
        args.addAll(sourcePaths);
        int result = compiler.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, result, "生成プログラム Java の javac 失敗:\n" + err.toString(StandardCharsets.UTF_8));
    }
}
