package jp.cobolinsight.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.linemap.MappingKind;
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
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies annotated stubs for untranslatable syntax and N:1 line mapping. Confirms that
 * EXEC CICS (SYK008) and EXEC SQL (SYK006/007) fall through to annotated stubs, that working-storage
 * SQL directives fall through to annotated comments, that the line map covers every generated line
 * with deterministic anchorIds, that all 9 generated Java sources compile deterministically, and that
 * level-88 predicates land on the record class.
 */
class EmbeddedStubTranspileTest {

    private static final List<String> ALL = List.of("SYK001.cbl", "SYK002.cbl", "SYK003.cbl",
            "SYK004.cbl", "SYK005.cbl", "SYK006.cbl", "SYK007.cbl", "SYK008.cbl", "SYK009.cbl");

    private static TranspileResult tr(String sample, TargetLanguage language) {
        return Transpiler.transpile(SampleModels.model(sample), SampleModels.sourceText(sample),
                language);
    }

    private static String file(TranspileResult result, String name) {
        return result.files().stream().filter(f -> f.fileName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("生成ファイルが無い: " + name)).content();
    }

    private static String program(TranspileResult result, String suffix) {
        return result.files().stream().filter(f -> f.fileName().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("プログラムファイルが無い: " + suffix)).content();
    }

    // ---- EXEC CICS annotated stub (SYK008) ----

    @Test
    void cicsBecomesRaiseStubWithOperandsInNote() {
        String py = program(tr("SYK008.cbl", TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains(
                "# [直訳不能: EXEC CICS RECEIVE MAP は直訳不能 "
                        + "(INTO=WS-受注入力マップ, MAP=SYKM01, MAPSET=SYKMAP1)]"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC CICS RECEIVE MAP は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC CICS SEND MAP は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC CICS RETURN は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC CICS XCTL は直訳不能\")"), py);
        // The PROGRAM operand of XCTL is included in the note
        assertTrue(py.contains("PROGRAM=SYK009"), py);
        // Original comment preserved
        assertTrue(py.contains("# EXEC CICS"), py);
    }

    @Test
    void cicsBecomesThrowStubInJava() {
        String java = program(tr("SYK008.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains(
                "if (true) throw new UnsupportedOperationException("
                        + "\"EXEC CICS RECEIVE MAP は直訳不能\");"), java);
        assertTrue(java.contains(
                "if (true) throw new UnsupportedOperationException(\"EXEC CICS XCTL は直訳不能\");"),
                java);
    }

    @Test
    void cicsMappingIsManyToOneOverBlockLines() {
        TranspileResult result = tr("SYK008.cbl", TargetLanguage.PYTHON);
        LineMappingEntry receive = result.lineMap().stream()
                .filter(e -> e.note().startsWith("EXEC CICS RECEIVE MAP は直訳不能"))
                .findFirst().orElseThrow(() -> new AssertionError("RECEIVE MAP の対応が無い"));
        assertEquals(34, receive.cobolLines().startLine(), "COBOL 開始行");
        assertEquals(38, receive.cobolLines().endLine(), "COBOL 終了行");
        assertEquals(MappingKind.MANY_TO_ONE, receive.mappingKind(), "N:1");
    }

    // ---- EXEC SQL annotated stub (SYK006) ----

    @Test
    void sqlBecomesRaiseStub() {
        String py = program(tr("SYK006.cbl", TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("raise NotImplementedError(\"EXEC SQL SELECT は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC SQL DECLARE CURSOR は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC SQL OPEN は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC SQL FETCH は直訳不能\")"), py);
        assertTrue(py.contains("raise NotImplementedError(\"EXEC SQL CLOSE は直訳不能\")"), py);

        String java = program(tr("SYK006.cbl", TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains(
                "if (true) throw new UnsupportedOperationException(\"EXEC SQL INSERT は直訳不能\");"),
                java);
    }

    @Test
    void sqlMappingIsManyToOne() {
        TranspileResult result = tr("SYK006.cbl", TargetLanguage.JAVA);
        assertTrue(result.lineMap().stream().anyMatch(e -> e.note().startsWith("EXEC SQL")
                && e.mappingKind() == MappingKind.MANY_TO_ONE), "EXEC SQL が N:1 で対応表に載る");
    }

    // ---- Working-storage SQL directives -> annotated comment (SYK006/007) ----

    @Test
    void workingStorageSqlDirectivesBecomeComments() {
        for (String sample : List.of("SYK006.cbl", "SYK007.cbl")) {
            String py = program(tr(sample, TargetLanguage.PYTHON), "_program.py");
            assertTrue(py.contains("INCLUDE SQLCA"), sample + " INCLUDE SQLCA コメント: " + py);
            assertTrue(py.contains("BEGIN DECLARE SECTION"), sample + " BEGIN DECLARE SECTION");
            assertTrue(py.contains("END DECLARE SECTION"), sample + " END DECLARE SECTION");
            String java = program(tr(sample, TargetLanguage.JAVA), "Program.java");
            assertTrue(java.contains("INCLUDE SQLCA"), sample + " Java INCLUDE SQLCA");
        }
        TranspileResult result = tr("SYK006.cbl", TargetLanguage.PYTHON);
        assertTrue(result.lineMap().stream().anyMatch(e -> e.note().contains("作業部")
                && e.cobolLines().startLine() == 43), "INCLUDE SQLCA(43行)が対応表に載る");
    }

    // ---- Level-88 predicate lands on the record class ----

    @Test
    void conditionPredicateGeneratedInRecordClass() {
        String pyRec = file(tr("SYK006.cbl", TargetLanguage.PYTHON), "WS_制御フラグ.py");
        assertTrue(pyRec.contains("def is_WS_EOF("), pyRec);
        String javaRec = file(tr("SYK006.cbl", TargetLanguage.JAVA), "WS_制御フラグ.java");
        assertTrue(javaRec.contains("public boolean is_WS_EOF("), javaRec);
    }

    // ---- Determinism (all 9 samples, both languages) ----

    @Test
    void allSamplesAreDeterministic() {
        for (String sample : ALL) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult a = tr(sample, language);
                TranspileResult b = tr(sample, language);
                assertEquals(a.files().size(), b.files().size(), sample + " 生成ファイル数");
                for (int i = 0; i < a.files().size(); i++) {
                    assertEquals(a.files().get(i).content(), b.files().get(i).content(),
                            sample + " / " + language + " の再生成が一致");
                }
                assertEquals(a.lineMap(), b.lineMap(), sample + " / " + language + " の行対応が一致");
            }
        }
    }

    // ---- anchorId is deterministic and sorted as <programId>#NNNN ----

    @Test
    void anchorsAreProgramScopedAndSorted() {
        Pattern anchor = Pattern.compile("^[^#]+#\\d{4}$");
        for (String sample : ALL) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult result = tr(sample, language);
                String programId = result.programId();
                int previousStart = 0;
                TreeSet<String> seen = new TreeSet<>();
                for (LineMappingEntry entry : result.lineMap()) {
                    assertTrue(anchor.matcher(entry.anchorId()).matches(),
                            sample + " anchorId 形式: " + entry.anchorId());
                    assertTrue(entry.anchorId().startsWith(programId + "#"),
                            sample + " anchorId 接頭辞: " + entry.anchorId());
                    assertTrue(seen.add(entry.anchorId()),
                            sample + " anchorId 重複: " + entry.anchorId());
                    assertTrue(entry.cobolLines().startLine() >= previousStart,
                            sample + " COBOL 開始行の昇順整列");
                    previousStart = entry.cobolLines().startLine();
                }
            }
        }
    }

    // ---- Line map covers every generated line (excluding structured-syntax scaffolding) ----

    @Test
    void lineMapCoversAllContentLines() {
        for (String sample : ALL) {
            for (TargetLanguage language : TargetLanguage.values()) {
                TranspileResult result = tr(sample, language);
                for (GeneratedFile f : result.files()) {
                    if (f.fileName().equals("cobol_runtime.py")
                            || f.fileName().equals("CobolRuntime.java")) {
                        continue; // The input-independent runtime is out of scope for translation
                    }
                    assertFileCovered(sample, language, f, result.lineMap());
                }
            }
        }
    }

    private void assertFileCovered(String sample, TargetLanguage language, GeneratedFile f,
            List<LineMappingEntry> map) {
        TreeSet<Integer> covered = new TreeSet<>();
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (LineMappingEntry e : map) {
            if (!e.generatedFile().equals(f.fileName())) {
                continue;
            }
            for (int l = e.generatedLines().startLine(); l <= e.generatedLines().endLine(); l++) {
                covered.add(l);
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
        }
        assertFalse(covered.isEmpty(), sample + " / " + f.fileName() + " に対応が無い");
        String[] lines = f.content().split("\n", -1);
        // The checked range runs from the minimum to the maximum mapped line. The package
        // declaration, imports, and class-level declarations are not derived from COBOL statements,
        // so they sit outside this range and are excluded from the check.
        for (int l = min; l <= max; l++) {
            if (covered.contains(l)) {
                continue;
            }
            String text = l - 1 < lines.length ? lines[l - 1].trim() : "";
            assertTrue(isStructural(text, language), sample + " / " + language + " / " + f.fileName()
                    + " の未対応行 L" + l + ": [" + text + "]");
        }
    }

    /** Whether the line is scaffolding with no corresponding COBOL statement, such as a control-structure continuation, closing brace, or filler line. */
    private static boolean isStructural(String trimmed, TargetLanguage language) {
        if (trimmed.isEmpty()) {
            return true;
        }
        if (language == TargetLanguage.JAVA) {
            return trimmed.equals("}") || trimmed.startsWith("} else");
        }
        return trimmed.equals("pass") || trimmed.equals("else:") || trimmed.startsWith("elif ");
    }

    // ---- All 9 generated Java sources compile ----

    @Test
    void allSamplesGeneratedJavaCompiles(@TempDir Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        for (String sample : ALL) {
            TranspileResult java = tr(sample, TargetLanguage.JAVA);
            Path src = Files.createDirectories(tempDir.resolve(sample.replace('.', '_')).resolve("src"));
            Path out = Files.createDirectories(tempDir.resolve(sample.replace('.', '_')).resolve("out"));
            List<String> sourcePaths = new ArrayList<>();
            for (GeneratedFile g : java.files()) {
                if (!g.fileName().endsWith(".java")) {
                    continue;
                }
                Path path = src.resolve(g.fileName());
                Files.writeString(path, g.content(), StandardCharsets.UTF_8);
                sourcePaths.add(path.toString());
            }
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", out.toString()));
            args.addAll(sourcePaths);
            int rc = compiler.run(null, null, err, args.toArray(new String[0]));
            assertEquals(0, rc, sample + " の生成 Java の javac 失敗:\n"
                    + err.toString(StandardCharsets.UTF_8));
        }
    }
}
