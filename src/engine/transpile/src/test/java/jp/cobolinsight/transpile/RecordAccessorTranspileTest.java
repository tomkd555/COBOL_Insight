package jp.cobolinsight.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates the record from SYKCPY1.cpy (via SYK003) into Python/Java, and verifies determinism, layout
 * agreement, and whether the Java compiles. The ground truth is the layout resolved by
 * {@link RecordLayoutResolver} (order number@0 / order date@10 / YMD REDEFINES@10 / total amount@24 len6 /
 * detail line OCCURS@32 element 22 / process kind@252 level 88) and WS-作業項目 (WS-I S9(04) COMP@0 len2 /
 * WS-合計@2 len6 / count@8 len5).
 */
class RecordAccessorTranspileTest {

    private static final String PROGRAM = "SYK003.cbl";

    private CobolSemanticModel model() {
        return SampleModels.model(PROGRAM);
    }

    private LayoutField orderLayout() {
        return RecordLayoutResolver.resolve(SampleModels.findItem(model(), "SYK1-受注レコード"));
    }

    private static String content(TranspileResult result, String fileName) {
        return result.files().stream().filter(f -> f.fileName().equals(fileName)).findFirst()
                .orElseThrow(() -> new AssertionError("生成ファイルが無い: " + fileName)).content();
    }

    private static int offsetOf(LayoutField layout, String name) {
        return layout.find(name).orElseThrow().offset();
    }

    private static int lengthOf(LayoutField layout, String name) {
        return layout.find(name).orElseThrow().byteLength();
    }

    @Test
    void generatesRuntimeAndRecordFilesForBothLanguages() {
        TranspileResult py = Transpiler.transpile(model(), TargetLanguage.PYTHON);
        TranspileResult java = Transpiler.transpile(model(), TargetLanguage.JAVA);
        assertNotNull(content(py, "cobol_runtime.py"));
        assertNotNull(content(java, "CobolRuntime.java"));
        assertNotNull(content(py, "SYK1_受注レコード.py"));
        assertNotNull(content(java, "SYK1_受注レコード.java"));
        assertNotNull(content(py, "WS_作業項目.py"));
        assertNotNull(content(py, "LK_チェック結果.py"));
    }

    @Test
    void rerunProducesByteIdenticalOutput() {
        for (TargetLanguage language : TargetLanguage.values()) {
            TranspileResult first = Transpiler.transpile(model(), language);
            TranspileResult second = Transpiler.transpile(model(), language);
            assertEquals(first.files().size(), second.files().size(), "生成ファイル数が一致");
            for (int i = 0; i < first.files().size(); i++) {
                assertEquals(first.files().get(i).fileName(), second.files().get(i).fileName());
                assertEquals(first.files().get(i).content(), second.files().get(i).content(),
                        language + " の生成内容が run-twice で一致");
            }
            assertEquals(first.lineMap(), second.lineMap(), language + " の行対応表が run-twice で一致");
        }
    }

    @Test
    void pythonAccessorsMatchLayoutOffsetsAndLengths() {
        LayoutField layout = orderLayout();
        String py = content(Transpiler.transpile(model(), TargetLanguage.PYTHON),
                "SYK1_受注レコード.py");

        assertTrue(py.contains("def get_SYK1_受注番号(self):"), py);
        assertTrue(py.contains("return decode_alphanumeric(self._data, "
                + offsetOf(layout, "SYK1-受注番号") + ", " + lengthOf(layout, "SYK1-受注番号") + ")"), py);
        assertTrue(py.contains("return decode_zoned(self._data, "
                + offsetOf(layout, "SYK1-受注日") + ", " + lengthOf(layout, "SYK1-受注日") + ")"), py);
        assertTrue(py.contains("return decode_packed(self._data, "
                + offsetOf(layout, "SYK1-受注金額合計") + ", " + lengthOf(layout, "SYK1-受注金額合計") + ")"), py);
        assertTrue(py.contains("encode_packed(self._data, "
                + offsetOf(layout, "SYK1-受注金額合計") + ", " + lengthOf(layout, "SYK1-受注金額合計")
                + ", True, value)"), py);
    }

    @Test
    void pythonOccursAccessorUsesIndexStride() {
        LayoutField layout = orderLayout();
        int stride = lengthOf(layout, "SYK1-明細行");
        int goodsOffset = offsetOf(layout, "SYK1-商品コード");
        String py = content(Transpiler.transpile(model(), TargetLanguage.PYTHON),
                "SYK1_受注レコード.py");
        assertTrue(py.contains("def get_SYK1_商品コード(self, i0):"), py);
        assertTrue(py.contains("return decode_alphanumeric(self._data, " + goodsOffset
                + " + i0 * " + stride + ", " + lengthOf(layout, "SYK1-商品コード") + ")"), py);
    }

    @Test
    void pythonConditionNamesBecomePredicates() {
        String py = content(Transpiler.transpile(model(), TargetLanguage.PYTHON),
                "SYK1_受注レコード.py");
        assertTrue(py.contains("def is_SYK1_新規登録(self):"), py);
        assertTrue(py.contains("return self.get_SYK1_処理区分() == \"1\""), py);
        assertTrue(py.contains("def is_SYK1_取消(self):"), py);
        assertTrue(py.contains("return self.get_SYK1_処理区分() == \"9\""), py);
    }

    @Test
    void pythonWorkingStorageCoversBinaryPackedZoned() {
        String py = content(Transpiler.transpile(model(), TargetLanguage.PYTHON), "WS_作業項目.py");
        assertTrue(py.contains("return decode_binary(self._data, 0, 2, True)"), py);
        assertTrue(py.contains("return decode_packed(self._data, 2, 6)"), py);
        assertTrue(py.contains("return decode_zoned(self._data, 8, 5)"), py);
        assertTrue(py.contains("encode_zoned(self._data, 8, 5, False, value)"), py);
    }

    @Test
    void javaAccessorsMatchLayoutOffsetsAndLengths() {
        LayoutField layout = orderLayout();
        String java = content(Transpiler.transpile(model(), TargetLanguage.JAVA),
                "SYK1_受注レコード.java");
        assertTrue(java.contains("public String get_SYK1_受注番号() {"), java);
        assertTrue(java.contains("return CobolRuntime.decodeAlphanumeric(data, "
                + offsetOf(layout, "SYK1-受注番号") + ", " + lengthOf(layout, "SYK1-受注番号") + ");"), java);
        assertTrue(java.contains("return CobolRuntime.decodePacked(data, "
                + offsetOf(layout, "SYK1-受注金額合計") + ", " + lengthOf(layout, "SYK1-受注金額合計") + ");"), java);
        assertTrue(java.contains("public String get_SYK1_商品コード(int i0) {"), java);
        assertTrue(java.contains("get_SYK1_処理区分().equals(\"1\")"), java);
    }

    @Test
    void lineMapIsDeterministicSortedAndAnnotated() {
        TranspileResult java = Transpiler.transpile(model(), TargetLanguage.JAVA);
        List<LineMappingEntry> map = java.lineMap();
        assertTrue(map.size() > 10, "行対応が十分に生成される: " + map.size());

        Set<String> anchors = new HashSet<>();
        Set<String> sourceIds = new HashSet<>();
        int previousStart = 0;
        boolean sawRedefines = false;
        boolean sawOccurs = false;
        for (LineMappingEntry entry : map) {
            assertTrue(entry.anchorId().startsWith("SYK003#"), entry.anchorId());
            assertTrue(anchors.add(entry.anchorId()), "anchorId が一意: " + entry.anchorId());
            assertTrue(entry.cobolLines().startLine() >= previousStart, "COBOL開始行の昇順整列");
            previousStart = entry.cobolLines().startLine();
            sourceIds.add(entry.cobolSourceId());
            if (entry.note().contains("REDEFINES")) {
                sawRedefines = true;
                assertEquals("SYKCPY1.cpy", entry.cobolSourceId(),
                        "REDEFINES はコピー句由来なので cobolSourceId がコピー句を指す");
            }
            if (entry.note().contains("OCCURS")) {
                sawOccurs = true;
            }
        }
        assertTrue(sawRedefines, "REDEFINES の注記が対応表に載る");
        assertTrue(sawOccurs, "OCCURS の注記が対応表に載る");
        assertTrue(sourceIds.contains("SYKCPY1.cpy"), "コピー句由来の行が区別される: " + sourceIds);
        assertTrue(sourceIds.contains("SYK003.cbl"), "プログラム本体由来の行が区別される: " + sourceIds);
    }

    @Test
    void generatedJavaCompiles(@TempDir Path tempDir) throws IOException {
        TranspileResult java = Transpiler.transpile(model(), TargetLanguage.JAVA);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "システム Java コンパイラが利用可能であること");

        Path src = Files.createDirectories(tempDir.resolve("src"));
        Path out = Files.createDirectories(tempDir.resolve("out"));
        List<String> sourcePaths = new ArrayList<>();
        for (GeneratedFile file : java.files()) {
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
        int result = compiler.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, result, "javac 失敗:\n" + err.toString(StandardCharsets.UTF_8));
    }
}
