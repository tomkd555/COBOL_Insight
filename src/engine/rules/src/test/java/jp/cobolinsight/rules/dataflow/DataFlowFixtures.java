package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.analysis.dataflow.DataFlowEngine;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.dataflow.DataFlowFacts;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.ParseOutcome;
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
 * データフロー段ルールのテスト補助。samples および合成ソースを実パーサー(cobol-frontend)で解析し、
 * CFG・不動点結果(DataFlowFacts)・SourceTextIndex を artifact として載せた AnalysisContext を組む。
 * 事実の供給経路は {@code parse → CfgBuilder.build → DataFlowEngine.analyzeAll} で、消費側ルールが
 * 本番で受け取るのと同じ artifact 形をテストでも再現する。制御フロー段の {@code CfgFixtures} と
 * 同じ構成を採る。
 */
final class DataFlowFixtures {

    static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static AnalysisContext samplesContext;

    private DataFlowFixtures() {
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

    /** model と texts から、CFG と不動点結果を載せた DATA_FLOW 段の AnalysisContext を組む。 */
    static AnalysisContext context(List<CobolSemanticModel> models, Map<String, String> texts) {
        List<ControlFlowGraph> graphs = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            graphs.add(CfgBuilder.build(model));
        }
        ControlFlowGraphs cfgs = new ControlFlowGraphs(graphs);
        DataFlowFacts facts = DataFlowEngine.analyzeAll(models, cfgs);
        Map<Class<?>, Object> artifacts = Map.of(
                SourceTextIndex.class, new SourceTextIndex(texts),
                ControlFlowGraphs.class, cfgs,
                DataFlowFacts.class, facts);
        return AnalysisContext.of(models, List.of(), List.of(), List.of(), Optional.empty(),
                artifacts);
    }

    /** samples 全体(cobol 9本 + copybook)を解析した DATA_FLOW 段の AnalysisContext。 */
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
                    .parse(decoded(file.toString(), text), List.of(SAMPLES.resolve("copybook")));
            models.add(outcome.value().orElseThrow(() -> new AssertionError(
                    file + " のパースが失敗した: " + outcome.failureFinding().orElse(null))));
        }
        samplesContext = context(models, texts);
        return samplesContext;
    }

    /** samples の cobol/<name>.cbl と一致する sourceFile 文字列。 */
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
