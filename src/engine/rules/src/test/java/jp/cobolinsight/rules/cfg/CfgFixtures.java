package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.frontend.bms.BmsModelMapper;
import jp.cobolinsight.frontend.bms.BmsParseResult;
import jp.cobolinsight.frontend.bms.BmsSourceParser;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.jcl.JclJobModel;
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
 * Test helper for the CFG-stage rules. Parses samples and synthetic sources with the real
 * parsers and assembles an AnalysisContext carrying the built CFGs, SourceTextIndex and
 * BmsMapset as artifacts. The CFG is built by applying {@link CfgBuilder#build} to each model
 * and bundling the results with {@link ControlFlowGraphs}
 * (a pre-normalization CFG; GotoNormalizer is not applied).
 */
final class CfgFixtures {

    static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static AnalysisContext samplesContext;

    private CfgFixtures() {
    }

    /** Writes a synthetic fixture text to a file and parses it with the real parser. */
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

    /** Builds a CONTROL_FLOW-stage AnalysisContext from models and texts, with the CFG built and attached. */
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

    /** CONTROL_FLOW-stage AnalysisContext parsed from the whole samples set (9 cobol files + copybook + BMS). */
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

    /** Returns the model whose sourceFile matches samples/cobol/<name>.cbl. */
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
     * Wraps text in a DecodedSource. offsets is a mapping from character position to UTF-8 byte
     * position; the second char of a surrogate pair also gets the byte position of the code point's start.
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
