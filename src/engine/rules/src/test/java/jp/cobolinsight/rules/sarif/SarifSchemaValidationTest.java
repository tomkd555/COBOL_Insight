package jp.cobolinsight.rules.sarif;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.rules.BuiltinRules;
import jp.cobolinsight.rules.SourceTextIndex;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SarifWriter の出力が SARIF 2.1.0 の公式JSONスキーマ(src/test/resources/sarif)に違反しない
 * ことの検証。合成した finding 群と、samples/ 全体を構文段の全ルールで lint した結果の両方を
 * 対象とする。
 */
class SarifSchemaValidationTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static Schema loadSchema() {
        InputStream in = SarifSchemaValidationTest.class
                .getResourceAsStream("/sarif/sarif-schema-2.1.0.json");
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_4).getSchema(in);
    }

    @Test
    void syntheticFindingsProduceSchemaValidSarif() {
        List<Rule> rules = BuiltinRules.all();
        // 1件目のファイル名には空白・#・非ASCII文字を含める。URI へ変換したうえでスキーマへ
        // 適合することまで確認する。
        List<Finding> findings = List.of(
                Finding.of("R026", FindingLevel.ERROR, "認証情報の直書き",
                        new SourcePosition("cobol\\B 資産#1.cbl", 5, 12,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)),
                Finding.of("R008", FindingLevel.WARNING, "THRUなしPERFORM",
                        new SourcePosition("cobol/A.cbl", 10, 12,
                                SourcePosition.UNKNOWN_BYTE_OFFSET)),
                new Finding("R004", FindingLevel.ERROR, "ON SIZE ERROR 句が無い",
                        new SourcePosition("cobol/A.cbl", 20, 1,
                                SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(), List.of(fixSuggestion())));

        List<Error> errors = loadSchema()
                .validate(SarifWriter.toJson(rules, findings), InputFormat.JSON);

        assertEquals(List.of(), errors, "SARIF 2.1.0スキーマ違反が0件であること");
    }

    /** 挿入(空範囲)と置換の2編集を持つ修正案。fixes の直列化がスキーマへ適合することを検証する。 */
    private static FixSuggestion fixSuggestion() {
        SourcePosition insertAt =
                new SourcePosition("cobol/A.cbl", 20, 40, SourcePosition.UNKNOWN_BYTE_OFFSET);
        SourceRange insertion = new SourceRange(insertAt, insertAt);
        SourceRange replacement = new SourceRange(
                new SourcePosition("cobol/A.cbl", 21, 12, SourcePosition.UNKNOWN_BYTE_OFFSET),
                new SourcePosition("cobol/A.cbl", 21, 20, SourcePosition.UNKNOWN_BYTE_OFFSET));
        return new FixSuggestion("ON SIZE ERROR 句を付与する",
                List.of(new TextEdit(insertion, "\n               ON SIZE ERROR"),
                        new TextEdit(replacement, "END-ADD")));
    }

    /** 汚染経路付きの finding が codeFlows(threadFlows → locations)としてスキーマへ適合すること。 */
    @Test
    void codeFlowFindingsProduceSchemaValidSarif() {
        List<Rule> rules = BuiltinRules.all();
        List<Finding> findings = List.of(
                new Finding("R020", FindingLevel.ERROR, "動的SQLへの外部入力の組込",
                        new SourcePosition("cobol/A.cbl", 30, 12,
                                SourcePosition.UNKNOWN_BYTE_OFFSET),
                        List.of(new CodeFlow(List.of(
                                new CodeFlowStep(new SourcePosition("cobol/A.cbl", 10, 16,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET),
                                        "WS-COND が外部入力を受け取る"),
                                new CodeFlowStep(new SourcePosition("cobol/A.cbl", 30, 12,
                                        SourcePosition.UNKNOWN_BYTE_OFFSET),
                                        "WS-COND を動的SQLの文字列へ組み込む")))),
                        List.of(fixSuggestion())));

        List<Error> errors = loadSchema()
                .validate(SarifWriter.toJson(rules, findings), InputFormat.JSON);

        assertEquals(List.of(), errors, "codeFlows もSARIF 2.1.0スキーマ違反が0件であること");
    }

    @Test
    void samplesLintResultProducesSchemaValidSarif() {
        List<Rule> rules = BuiltinRules.all();
        Map<String, String> texts = new LinkedHashMap<>();
        List<CobolSemanticModel> models = new ArrayList<>();
        for (Path file : listFiles(SAMPLES.resolve("copybook"), ".cpy")) {
            texts.put(file.toString(), readText(file));
        }
        for (Path file : listFiles(SAMPLES.resolve("cobol"), ".cbl")) {
            String text = readText(file);
            texts.put(file.toString(), text);
            ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                    .parse(decoded(file.toString(), text),
                            List.of(SAMPLES.resolve("copybook")));
            models.add(outcome.value().orElseThrow(() -> new AssertionError(
                    file + " のパースが失敗した: " + outcome.failureFinding().orElse(null))));
        }
        AnalysisContext context = AnalysisContext.of(models, List.of(), List.of(), List.of(),
                Optional.empty(), Map.of(SourceTextIndex.class, new SourceTextIndex(texts)));
        List<Finding> findings = new ArrayList<>();
        for (Rule rule : rules) {
            findings.addAll(rule.evaluate(context));
        }
        assertFalse(findings.isEmpty(), "samplesのlint結果が空でないこと(検証の空振り防止)");

        List<Error> errors = loadSchema()
                .validate(SarifWriter.toJson(rules, findings), InputFormat.JSON);

        assertEquals(List.of(), errors, "samplesのlint結果もSARIF 2.1.0スキーマ違反が0件であること");
    }

    private static List<Path> listFiles(Path dir, String extension) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                            .endsWith(extension))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * テキストを DecodedSource へ包む。offsets は文字位置からUTF-8バイト位置への対応表であり、
     * サロゲートペアの2文字目にもコードポイント先頭のバイト位置を入れる。
     */
    private static DecodedSource decoded(String path, String text) {
        int[] offsets = new int[text.length()];
        int byteOffset = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            for (int j = 0; j < charCount; j++) {
                offsets[i + j] = byteOffset;
            }
            byteOffset += new String(Character.toChars(codePoint))
                    .getBytes(StandardCharsets.UTF_8).length;
            i += charCount;
        }
        return new DecodedSource(path, text, text.getBytes(StandardCharsets.UTF_8), offsets,
                new EncodingInfo("UTF-8", 1.0, false, false));
    }
}
