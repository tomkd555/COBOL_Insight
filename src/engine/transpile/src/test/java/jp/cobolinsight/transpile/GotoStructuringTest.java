package jp.cobolinsight.transpile;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GO TO を含む手続きの構造化制御対訳を検証する。SYK002 の無条件前方 GO TO(9000→4010)は順次実行へ、
 * 合成した検証用ソースの後方 GO TO はループへ、条件付き GO TO は if へ還元されること、生成 Java が
 * コンパイルできること、再生成が決定論であること、GO TO 起源の複製が N:1 で行対応へ載ることを確認する。
 */
class GotoStructuringTest {

    private static final Path GOTO_DIR =
            repoRoot().resolve("src/engine/transpile/src/test/resources/goto");

    /** 作業ディレクトリがモジュール配下でも解決できるよう、samples を持つ親をリポジトリルートとして遡る。 */
    private static Path repoRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null && !Files.isDirectory(dir.resolve("samples"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("samples ディレクトリが見つからない");
        }
        return dir;
    }

    private static CobolSemanticModel syntheticModel(String name) {
        try {
            byte[] bytes = Files.readAllBytes(GOTO_DIR.resolve(name));
            String text = new String(bytes, StandardCharsets.UTF_8);
            DecodedSource src = new DecodedSource(GOTO_DIR.resolve(name).toString(), text, bytes,
                    new int[text.length()], new EncodingInfo("UTF-8", 1.0, false, false));
            ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser().parse(src, List.of());
            return outcome.value().orElseThrow(() -> new AssertionError(
                    name + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String syntheticText(String name) {
        try {
            return new String(Files.readAllBytes(GOTO_DIR.resolve(name)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String program(TranspileResult result, String suffix) {
        return result.files().stream().filter(f -> f.fileName().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("プログラムファイルが無い: " + suffix)).content();
    }

    /** header で始まるメソッド本体を、次のメソッド宣言 delim の手前まで切り出す。 */
    private static String methodSlice(String program, String header, String delim) {
        int start = program.indexOf(header);
        assertTrue(start >= 0, "メソッドが無い: " + header);
        int end = program.indexOf(delim, start + header.length());
        return end < 0 ? program.substring(start) : program.substring(start, end);
    }

    // ---- SYK002: 無条件前方 GO TO(9000 → 4010)----

    @Test
    void syk002ForwardGotoBecomesSequentialControl() {
        String py = program(Transpiler.transpile(SampleModels.model("SYK002.cbl"),
                SampleModels.sourceText("SYK002.cbl"), TargetLanguage.PYTHON), "_program.py");
        String method = methodSlice(py, "def _9000_緊急再更新処理(self):", "\n    def ");
        int displayAt = method.indexOf("print(\"SYK002 レコードロック検出のため再更新を実施\")");
        int rewriteAt = method.indexOf("REWRITE SYK2-受注マスタレコード");
        assertTrue(displayAt >= 0, method);
        assertTrue(rewriteAt >= 0, "GO TO の飛び先 4010 の REWRITE が 9000 へ順次化されている: " + method);
        assertTrue(displayAt < rewriteAt, "DISPLAY の後に 4010 の REWRITE へ制御が移る: " + method);
        assertFalse(method.contains("還元できず"), "GO TO が構造化される(退避経路でない): " + method);
        assertTrue(method.contains("を以降の順次実行へ構造化"), "GO TO 構造化の注記が付く: " + method);
    }

    @Test
    void syk002DuplicatedTargetLineMapsToMultipleGeneratedLocations() {
        TranspileResult py = Transpiler.transpile(SampleModels.model("SYK002.cbl"),
                SampleModels.sourceText("SYK002.cbl"), TargetLanguage.PYTHON);
        // COBOL 130 行(4010 の REWRITE)は、_4010 の自メソッドと _9000 への複製の双方へ対応する(1:N)。
        List<LineMappingEntry> rewrite = py.lineMap().stream()
                .filter(e -> e.cobolLines().startLine() == 130 && e.cobolLines().endLine() == 130)
                .filter(e -> e.generatedFile().endsWith("_program.py"))
                .toList();
        assertTrue(rewrite.size() >= 2, "REWRITE(130 行)が複数の生成箇所へ対応する: " + rewrite);
        assertTrue(rewrite.stream().anyMatch(e -> e.note().contains("複製")),
                "複製側の対応に note が付く: " + rewrite);
        assertTrue(py.lineMap().stream().anyMatch(
                e -> e.cobolLines().startLine() == 124 && e.note().contains("順次実行へ構造化")),
                "GO TO 行(124)に構造化の注記が載る");
    }

    // ---- 合成: 後方 GO TO = ループ ----

    @Test
    void syntheticBackwardGotoBecomesWhileLoop() {
        CobolSemanticModel model = syntheticModel("GOTOLOOP.cbl");
        String text = syntheticText("GOTOLOOP.cbl");
        String py = program(Transpiler.transpile(model, text, TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("while not (self.WS_COUNTER > self.WS_LIMIT):"), py);
        assertTrue(py.contains("self.WS_COUNTER = self.WS_COUNTER + 1"), py);
        assertFalse(py.contains("還元できず"), py);

        String java = program(Transpiler.transpile(model, text, TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains("while (!(WS_COUNTER > WS_LIMIT)) {"), java);
        assertTrue(java.contains("WS_COUNTER = WS_COUNTER + 1;"), java);
    }

    // ---- 合成: 条件付き GO TO = if ----

    @Test
    void syntheticConditionalGotoBecomesIf() {
        CobolSemanticModel model = syntheticModel("GOTOCOND.cbl");
        String text = syntheticText("GOTOCOND.cbl");
        String py = program(Transpiler.transpile(model, text, TargetLanguage.PYTHON), "_program.py");
        assertTrue(py.contains("if self.WS_KUBUN == \"9\":"), py);
        assertTrue(py.contains("self.WS_RESULT = \"A\""), py);
        assertTrue(py.contains("self.WS_COUNT = self.WS_COUNT + 1"), py);
        assertFalse(py.contains("還元できず"), py);

        String java = program(Transpiler.transpile(model, text, TargetLanguage.JAVA), "Program.java");
        assertTrue(java.contains("if (WS_KUBUN.equals(\"9\")) {"), java);
        assertTrue(java.contains("WS_RESULT = \"A\";"), java);
    }

    // ---- 決定論 ----

    @Test
    void gotoStructuringIsDeterministic() {
        for (TargetLanguage language : TargetLanguage.values()) {
            TranspileResult a = Transpiler.transpile(SampleModels.model("SYK002.cbl"),
                    SampleModels.sourceText("SYK002.cbl"), language);
            TranspileResult b = Transpiler.transpile(SampleModels.model("SYK002.cbl"),
                    SampleModels.sourceText("SYK002.cbl"), language);
            assertEquals(a.files().size(), b.files().size());
            for (int i = 0; i < a.files().size(); i++) {
                assertEquals(a.files().get(i).content(), b.files().get(i).content(),
                        "SYK002 / " + language + " の再生成が一致");
            }
            assertEquals(a.lineMap(), b.lineMap(), "SYK002 / " + language + " の行対応が一致");
        }
    }

    // ---- 生成 Java のコンパイル ----

    @Test
    void structuredGotoJavaCompiles(@TempDir Path tempDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path out = Files.createDirectories(tempDir.resolve("out"));
        List<String> sourcePaths = new ArrayList<>();

        List<TranspileResult> results = new ArrayList<>();
        results.add(Transpiler.transpile(SampleModels.model("SYK002.cbl"),
                SampleModels.sourceText("SYK002.cbl"), TargetLanguage.JAVA));
        results.add(Transpiler.transpile(syntheticModel("GOTOLOOP.cbl"),
                syntheticText("GOTOLOOP.cbl"), TargetLanguage.JAVA));
        results.add(Transpiler.transpile(syntheticModel("GOTOCOND.cbl"),
                syntheticText("GOTOCOND.cbl"), TargetLanguage.JAVA));

        for (TranspileResult result : results) {
            for (GeneratedFile file : result.files()) {
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
        int rc = compiler.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, rc, "構造化 GO TO の生成 Java の javac 失敗:\n"
                + err.toString(StandardCharsets.UTF_8));
    }
}
