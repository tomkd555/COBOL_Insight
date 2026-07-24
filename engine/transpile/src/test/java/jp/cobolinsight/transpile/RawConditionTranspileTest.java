package jp.cobolinsight.transpile;

import jp.cobolinsight.engineapi.linemap.LineMappingEntry;
import jp.cobolinsight.engineapi.transpile.GeneratedFile;
import jp.cobolinsight.engineapi.transpile.TargetLanguage;
import jp.cobolinsight.engineapi.transpile.TranspileResult;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直訳不能な条件(未宣言項目・特殊レジスタを含み {@code PCond.Raw} へ落ちる条件)が、恒真値へ黙って
 * 消えず、原文の条件テキストと注記付きで出ることを検証する。実素材は SYK006 の
 * {@code PERFORM 3100-抽出データフェッチ UNTIL SQLCODE = 100}(SQLCODE は未宣言の特殊レジスタ)。
 */
class RawConditionTranspileTest {

    private static TranspileResult transpile(TargetLanguage language) {
        return Transpiler.transpile(SampleModels.model("SYK006.cbl"),
                SampleModels.sourceText("SYK006.cbl"), language);
    }

    private static String program(TranspileResult result, String suffix) {
        return result.files().stream().filter(f -> f.fileName().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("プログラムファイルが無い: " + suffix)).content();
    }

    @Test
    void pythonKeepsRawConditionInlineWithoutSilentTrue() {
        String py = program(transpile(TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("while not (SQLCODE == 100):"),
                "原文条件が逐語で描画され SQLCODE の生名が残ること: " + py);
        assertFalse(py.contains("while not (True):"), "恒真値への黙った条件消失が無いこと: " + py);
    }

    @Test
    void pythonNotEqualRawConditionIsValidPython() {
        String py = program(transpile(TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("if SQLCODE != 0:"),
                "COBOL の NOT = が否定した Python 演算子 != へ写り、原名が残ること: " + py);
        assertFalse(py.contains("SQLCODE NOT == 0"),
                "NOT を素通しした不正 Python が出ないこと: " + py);
    }

    @Test
    void pythonLogicalOrRawConditionIsValidPython() {
        String py = program(Transpiler.transpile(SampleModels.model("SYK008.cbl"),
                SampleModels.sourceText("SYK008.cbl"), TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("== SPACES or "),
                "COBOL の論理演算子 OR が Python の or へ写ること: " + py);
        assertFalse(py.contains(" OR "), "大文字 OR を素通しした不正 Python が出ないこと: " + py);
    }

    @Test
    void javaKeepsRawConditionAsVerbatimCommentAndCompiles(@TempDir Path tempDir)
            throws IOException {
        String java = program(transpile(TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains("while (!(Boolean.TRUE /* SQLCODE = 100 */)) {"),
                "直訳不能条件の原文がガードのコメントとして残ること: " + java);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path out = Files.createDirectories(tempDir.resolve("out"));
        List<String> sourcePaths = new ArrayList<>();
        for (GeneratedFile file : transpile(TargetLanguage.JAVA).files()) {
            if (!file.fileName().endsWith(".java")) {
                continue;
            }
            Path path = src.resolve(file.fileName());
            Files.writeString(path, file.content(), StandardCharsets.UTF_8);
            sourcePaths.add(path.toString());
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-d", out.toString()));
        args.addAll(sourcePaths);
        int rc = compiler.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, rc, "直訳不能条件を含む SYK006 の生成 Java の javac 失敗:\n"
                + err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void lineMapCarriesUntranslatableConditionNote() {
        TranspileResult py = transpile(TargetLanguage.PYTHON);
        List<LineMappingEntry> withNote = py.lineMap().stream()
                .filter(e -> e.note().contains("直訳不能"))
                .filter(e -> e.note().contains("SQLCODE"))
                .toList();
        assertFalse(withNote.isEmpty(),
                "直訳不能条件の注記(原文つき)が行対応に載ること: " + py.lineMap());
    }
}
