package jp.cobolinsight.cli;

import jp.cobolinsight.dataflow.CfgBuilder;
import jp.cobolinsight.dataflow.DataFlowEngine;
import jp.cobolinsight.encoding.CodePage;
import jp.cobolinsight.encoding.SourceDecoder;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.FixProducer;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.rules.SourceTextIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * `fix` の中核処理。資産フォルダの COBOL を復号・パースして制御フローグラフとデータフロー事実を組み、
 * 構文・制御フロー・データフローの各段階のルールを評価して findings を得る。各 finding のルールが
 * {@link Rule#fixProducer()} を持てば {@link FixProducer#produce} で修正案を取得し、原本ファイル別に
 * {@link TextEdit} 群を集約する。集約した編集をバイトスプライスで原本へ適用し、修正後バイト列・
 * 原本テキスト・修正後テキストを {@link FileFix} として返す。挿入行の固定形式整形は FixProducer が
 * 済ませている前提とし、本処理は範囲昇順・非重複の適用だけを担う(重複は適用器が拒否する)。
 *
 * <p>解析経路は engine-api の {@link CharsetProvider}/{@link CobolParser}(ServiceLoader 解決)を
 * 用い、バイトスプライスの桁引き・再符号化は原本を {@link SourceDecoder} で再復号した
 * {@link jp.cobolinsight.encoding.DecodedSource}(ByteOffsetTable 付き)で行う。原本ファイルは
 * 一切変更しない。
 */
public final class FixRunner {

    private static final String DECODE_FAILURE_RULE_ID = "decode-failure";
    private static final String COBOL_EXTENSION = ".cbl";
    private static final String COPYBOOK_EXTENSION = ".cpy";

    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides) {

        public Options {
            copybookSearchPaths = List.copyOf(copybookSearchPaths);
            codepageOverrides = Map.copyOf(codepageOverrides);
        }
    }

    /**
     * 1ファイルの修正結果。原本の相対パス・確定コードページ名・原本テキスト・修正後テキスト・
     * 修正後バイト列と、適用した修正案の説明群を持つ。修正後バイト列は原本と同一のコードページで
     * 符号化済みで、そのまま出力先へ書き出せる。
     *
     * <p>{@code copybook} が真のとき、この修正はコピー句由来である。コピー句は複数プログラムへ
     * 展開されるため原本を書き換えず差分提示に留め、{@code importers} に当該コピー句を取り込む
     * プログラムの相対パス(昇順)を併記する。プログラム本体の修正では {@code copybook} は偽、
     * {@code importers} は空。
     */
    public record FileFix(String relPath, String charsetName, String originalText, String fixedText,
            byte[] fixedBytes, List<String> descriptions, boolean copybook, List<String> importers) {

        public FileFix {
            descriptions = List.copyOf(descriptions);
            importers = List.copyOf(importers);
        }
    }

    public record Result(List<FileFix> fileFixes, List<Finding> analysisFindings, int fixCount) {

        public Result {
            fileFixes = List.copyOf(fileFixes);
            analysisFindings = List.copyOf(analysisFindings);
        }

        public long analysisErrors() {
            return analysisFindings.stream().filter(f -> f.level() == FindingLevel.ERROR).count();
        }
    }

    private enum FixKind {
        COBOL, COPYBOOK
    }

    private record FixFile(String relPath, Path absPath, FixKind kind) {

        String sourcePath() {
            return absPath.toString();
        }
    }

    private final SourceDecoder sourceDecoder = new SourceDecoder();
    private final jp.cobolinsight.fix.ByteSpliceApplier applier =
            new jp.cobolinsight.fix.ByteSpliceApplier();

    public Result run(Options options) {
        AnalysisServices services = AnalysisServices.load();
        CharsetProvider charsetProvider = first(services.charsetProviders(), "CharsetProvider");
        CobolParser cobolParser = first(services.cobolParsers(), "CobolParser");

        List<FixFile> files = discover(options);
        List<Finding> analysisFindings = new ArrayList<>();
        Map<String, byte[]> bytesByRel = new LinkedHashMap<>();
        Map<String, DecodedSource> decodedByRel = new LinkedHashMap<>();
        Map<String, String> textByPath = new LinkedHashMap<>();
        Map<String, FixFile> fileBySourcePath = new LinkedHashMap<>();

        for (FixFile file : files) {
            byte[] bytes = readBytes(file.absPath());
            bytesByRel.put(file.relPath(), bytes);
            String override = overrideOf(options, file);
            try {
                DecodedSource decoded = override == null
                        ? charsetProvider.decode(file.sourcePath(), bytes)
                        : charsetProvider.decode(file.sourcePath(), bytes, override);
                decodedByRel.put(file.relPath(), decoded);
                textByPath.put(decoded.path(), decoded.text());
                fileBySourcePath.put(decoded.path(), file);
            } catch (IllegalArgumentException e) {
                if (file.kind() == FixKind.COBOL) {
                    analysisFindings.add(Finding.of(DECODE_FAILURE_RULE_ID, FindingLevel.ERROR,
                            "復号に失敗した: " + e.getMessage(),
                            SourcePosition.fileStart(file.relPath())));
                }
            }
        }

        List<CobolSemanticModel> models = new ArrayList<>();
        for (FixFile file : files) {
            DecodedSource decoded = decodedByRel.get(file.relPath());
            if (file.kind() != FixKind.COBOL || decoded == null) {
                continue;
            }
            ParseOutcome<CobolSemanticModel> outcome =
                    cobolParser.parse(decoded, options.copybookSearchPaths());
            if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                analysisFindings.add(failure.finding());
                continue;
            }
            models.add(outcome.value().orElseThrow());
        }

        List<ControlFlowGraph> graphs = models.stream().map(CfgBuilder::build).toList();
        ControlFlowGraphs cfgs = new ControlFlowGraphs(graphs);
        DataFlowFacts dataFlowFacts = DataFlowEngine.analyzeAll(models, cfgs);
        AnalysisContext context = AnalysisContext.of(models, List.of(), List.of(), List.of(),
                Optional.empty(),
                Map.of(SourceTextIndex.class, new SourceTextIndex(textByPath),
                        ControlFlowGraphs.class, cfgs,
                        DataFlowFacts.class, dataFlowFacts));

        List<Rule> activeRules = Stream.of(
                        services.rules(AnalysisPhase.SYNTAX),
                        services.rules(AnalysisPhase.CONTROL_FLOW),
                        services.rules(AnalysisPhase.DATA_FLOW))
                .flatMap(List::stream)
                .filter(rule -> rule.id().startsWith("R"))
                .toList();

        // fixProducer を持つルールだけを評価し、その finding から編集を集約する。ルール由来の finding は
        // 修正案の材料であり、その重大度は fix の成否と無関係のため analysisFindings へは載せない
        // (analysisFindings は復号・パース失敗=解析不能を表す pipeline のエラーだけを保持する)。
        // finding.location().file() は意味モデルの sourceFile(=復号時に渡した原本パス文字列)と一致する。
        Map<String, List<TextEdit>> editsBySource = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> descBySource = new LinkedHashMap<>();
        for (Rule rule : activeRules) {
            FixProducer producer = rule.fixProducer().orElse(null);
            if (producer == null) {
                continue;
            }
            List<Finding> ruleFindings = rule.evaluate(context);
            for (Finding finding : ruleFindings) {
                FixSuggestion suggestion = producer.produce(finding, context).orElse(null);
                if (suggestion == null) {
                    continue;
                }
                String source = finding.location().file();
                editsBySource.computeIfAbsent(source, k -> new ArrayList<>())
                        .addAll(suggestion.edits());
                descBySource.computeIfAbsent(source, k -> new LinkedHashSet<>())
                        .add(suggestion.description());
            }
        }

        // コピー句由来の修正の影響範囲併記に用いる、プログラム相対パス→復号済みソースの索引。
        Map<String, String> programSourcesByRel = new LinkedHashMap<>();
        for (FixFile file : files) {
            if (file.kind() == FixKind.COBOL) {
                DecodedSource decoded = decodedByRel.get(file.relPath());
                if (decoded != null) {
                    programSourcesByRel.put(file.relPath(), decoded.text());
                }
            }
        }

        List<FileFix> fileFixes = new ArrayList<>();
        int fixCount = 0;
        for (Map.Entry<String, List<TextEdit>> entry : editsBySource.entrySet()) {
            FixFile file = fileBySourcePath.get(entry.getKey());
            if (file == null) {
                continue;
            }
            List<TextEdit> edits = entry.getValue();
            byte[] bytes = bytesByRel.get(file.relPath());
            String override = overrideOf(options, file);
            jp.cobolinsight.encoding.DecodedSource decoded = override == null
                    ? sourceDecoder.decode(bytes)
                    : sourceDecoder.decode(bytes, CodePage.fromName(override));
            byte[] fixedBytes = applier.apply(decoded, edits);
            Charset charset = decoded.encodingInfo().codePage().charset();
            boolean copybook = file.kind() == FixKind.COPYBOOK;
            List<String> importers = copybook
                    ? CopybookImporters.of(CopybookImporters.baseName(file.relPath()),
                            programSourcesByRel)
                    : List.of();
            fileFixes.add(new FileFix(file.relPath(),
                    decoded.encodingInfo().codePage().charsetName(),
                    decoded.text(), new String(fixedBytes, charset), fixedBytes,
                    new ArrayList<>(descBySource.get(entry.getKey())), copybook, importers));
            fixCount += edits.size();
        }
        fileFixes.sort(Comparator.comparing(FileFix::relPath));

        return new Result(fileFixes, analysisFindings, fixCount);
    }

    /** cobol ディレクトリの COBOL 本体と、コピー句探索パス配下のコピー句を発見する(相対パスの辞書順)。 */
    private static List<FixFile> discover(Options options) {
        Map<String, FixFile> byRel = new LinkedHashMap<>();
        collect(options.inputDir().resolve("cobol"), COBOL_EXTENSION, FixKind.COBOL,
                options.inputDir(), byRel);
        for (Path dir : options.copybookSearchPaths()) {
            collect(dir, COPYBOOK_EXTENSION, FixKind.COPYBOOK, options.inputDir(), byRel);
        }
        List<FixFile> files = new ArrayList<>(byRel.values());
        files.sort(Comparator.comparing(FixFile::relPath));
        return files;
    }

    private static void collect(Path dir, String extension, FixKind kind, Path inputDir,
            Map<String, FixFile> byRel) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                            .endsWith(extension))
                    .forEach(p -> {
                        String relPath = relativize(inputDir, p);
                        byRel.putIfAbsent(relPath, new FixFile(relPath, p, kind));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 入力フォルダ配下なら相対パス、そうでなければ「親ディレクトリ名/ファイル名」を相対パスとする。 */
    private static String relativize(Path inputDir, Path file) {
        Path base = inputDir.toAbsolutePath().normalize();
        Path abs = file.toAbsolutePath().normalize();
        if (abs.startsWith(base)) {
            return base.relativize(abs).toString().replace('\\', '/');
        }
        Path parent = abs.getParent();
        String dirName = parent == null ? "" : parent.getFileName().toString();
        return (dirName.isEmpty() ? "" : dirName + "/") + abs.getFileName();
    }

    private static String overrideOf(Options options, FixFile file) {
        String override = options.codepageOverrides().get(file.relPath());
        if (override == null) {
            override = options.codepageOverrides().get(file.absPath().getFileName().toString());
        }
        return override;
    }

    private static <T> T first(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
