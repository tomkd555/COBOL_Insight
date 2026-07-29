package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.bmsfrontend.BmsModelMapper;
import jp.cobolinsight.bmsfrontend.BmsParseResult;
import jp.cobolinsight.bmsfrontend.BmsSourceParser;
import jp.cobolinsight.cobolfrontend.Che4zCobolParser;
import jp.cobolinsight.dataflow.CfgBuilder;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.rules.SourceTextIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * CFG段ルールのテスト補助。samples および合成ソースを実パーサーで解析し、構築済みCFG・
 * SourceTextIndex・BmsMapset を artifact として載せた AnalysisContext を組み立てる。
 * CFG は {@link CfgBuilder#build} を各 model に適用し {@link ControlFlowGraphs} で束ねる
 * (正規化前の構築済みCFG。GotoNormalizer は使わない)。
 */
final class CfgFixtures {

    static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    private static AnalysisContext samplesContext;

    private CfgFixtures() {
    }

    /** 合成fixtureテキストをファイルへ書き出し、実パーサーで解析する。 */
    static CobolSemanticModel parse(Path dir, String fileName, String text, Path... copybookDirs) {
        Path file = dir.resolve(fileName);
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ParseOutcome<CobolSemanticModel> outcome =
                new Che4zCobolParser().parse(decoded(file.toString(), text), List.of(copybookDirs));
        return outcome.value().orElseThrow(() -> new AssertionError(
                fileName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
    }

    /** model と texts から、CFGを構築して載せた CONTROL_FLOW 段の AnalysisContext を組む。 */
    static AnalysisContext context(List<CobolSemanticModel> models, Map<String, String> texts) {
        return context(models, texts, List.of(), List.of());
    }

    static AnalysisContext context(List<CobolSemanticModel> models, Map<String, String> texts,
            List<BmsMapset> bmsMapsets, List<JclJobModel> jclJobs) {
        List<ControlFlowGraph> graphs = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            graphs.add(CfgBuilder.build(model));
        }
        Map<Class<?>, Object> artifacts = Map.of(
                SourceTextIndex.class, new SourceTextIndex(texts),
                ControlFlowGraphs.class, new ControlFlowGraphs(graphs));
        return AnalysisContext.of(models, jclJobs, List.of(), bmsMapsets, Optional.empty(),
                artifacts);
    }

    /** samples 全体(cobol 9本+copybook+BMS)を解析した CONTROL_FLOW 段の AnalysisContext。 */
    static synchronized AnalysisContext samples() {
        if (samplesContext != null) {
            return samplesContext;
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (Path file : listFiles(SAMPLES.resolve("copybook"), ".cpy")) {
            texts.put(file.toString(), readText(file));
        }
        List<CobolSemanticModel> models = new ArrayList<>();
        for (Path file : listFiles(SAMPLES.resolve("cobol"), ".cbl")) {
            String text = readText(file);
            texts.put(file.toString(), text);
            ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                    .parse(decoded(file.toString(), text),
                            List.of(SAMPLES.resolve("copybook")));
            models.add(outcome.value().orElseThrow(() -> new AssertionError(
                    file + " のパースが失敗した: " + outcome.failureFinding().orElse(null))));
        }
        List<BmsMapset> mapsets = new ArrayList<>();
        for (Path file : listFiles(SAMPLES.resolve("bms"), ".bms")) {
            BmsParseResult result = new BmsSourceParser().parse(readText(file));
            mapsets.addAll(BmsModelMapper.toEngineApi(result, file.toString()));
        }
        samplesContext = context(models, texts, List.copyOf(mapsets), List.of());
        return samplesContext;
    }

    /** samples の cobol/<name>.cbl と一致する sourceFile を持つ model を返す。 */
    static String samplesFile(String cobolBaseName) {
        return SAMPLES.resolve("cobol").resolve(cobolBaseName).toString();
    }

    private static List<Path> listFiles(Path dir, String extension) {
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
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
    static DecodedSource decoded(String path, String text) {
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
