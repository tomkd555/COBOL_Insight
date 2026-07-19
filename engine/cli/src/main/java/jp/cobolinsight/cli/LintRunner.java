package jp.cobolinsight.cli;

import jp.cobolinsight.bmsfrontend.BmsModelMapper;
import jp.cobolinsight.bmsfrontend.BmsParseError;
import jp.cobolinsight.bmsfrontend.BmsParseResult;
import jp.cobolinsight.bmsfrontend.BmsSourceParser;
import jp.cobolinsight.dataflow.CfgBuilder;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.rules.SourceTextIndex;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * `lint` の中核処理。資産フォルダのCOBOL・コピー句・BMSを復号し、COBOLをパースして
 * 制御フローグラフを構築し、BMSをマップモデルへ写像したうえで、構文段階(AnalysisPhase.SYNTAX)と
 * 制御フロー段階(AnalysisPhase.CONTROL_FLOW)のルールを実行して findings を返す。復号失敗・
 * パース失敗も errorレベルの finding として合流させる。出力は決定論とする: findings は
 * (ファイル・行・桁・ルールID・メッセージ)の昇順に正規化し、位置のファイルは
 * 入力フォルダからの相対パスへ揃える。
 */
public final class LintRunner {

    private static final String DECODE_FAILURE_RULE_ID = "decode-failure";

    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, Set<String> disabledRuleIds) {
    }

    public record Result(List<Finding> findings, List<String> analyzed, String sarifJson,
            int exitCode) {

        public Result {
            findings = List.copyOf(findings);
            analyzed = List.copyOf(analyzed);
        }

        public long countByLevel(FindingLevel level) {
            return findings.stream().filter(f -> f.level() == level).count();
        }

        public String summaryJson(String sarifFilePath) {
            JsonWriter writer = new JsonWriter();
            writer.beginObject();
            writer.name("analyzed").beginArray();
            for (String path : analyzed) {
                writer.value(path);
            }
            writer.endArray();
            writer.name("findingCount").value(findings.size())
                    .name("errors").value(countByLevel(FindingLevel.ERROR))
                    .name("warnings").value(countByLevel(FindingLevel.WARNING))
                    .name("notes").value(countByLevel(FindingLevel.NOTE))
                    .name("sarifFile").value(sarifFilePath.replace('\\', '/'))
                    .name("exitCode").value(exitCode)
                    .endObject();
            return writer.toString();
        }
    }

    /** lint対象の種別。COBOLはパース、COPYBOOKはテキスト索引のみ、BMSはマップモデルへ写像する。 */
    private enum LintKind {
        COBOL(".cbl"), COPYBOOK(".cpy"), BMS(".bms");

        final String extension;

        LintKind(String extension) {
            this.extension = extension;
        }
    }

    private record LintFile(String relPath, Path absPath, LintKind kind) {
    }

    private LintRunner() {
    }

    public static Result run(Options options) {
        AnalysisServices services = AnalysisServices.load();
        CharsetProvider charsetProvider =
                first(services.charsetProviders(), "CharsetProvider");
        CobolParser cobolParser = first(services.cobolParsers(), "CobolParser");

        List<LintFile> files = discover(options.inputDir());
        List<Finding> findings = new ArrayList<>();
        Map<String, String> textByPath = new LinkedHashMap<>();
        Map<Path, String> relByAbs = new HashMap<>();
        Map<String, DecodedSource> decodedByRel = new LinkedHashMap<>();

        for (LintFile file : files) {
            relByAbs.put(file.absPath().toAbsolutePath().normalize(), file.relPath());
            byte[] bytes = readBytes(file.absPath());
            try {
                String override = options.codepageOverrides().get(file.relPath());
                if (override == null) {
                    override = options.codepageOverrides()
                            .get(file.absPath().getFileName().toString());
                }
                DecodedSource decoded = override == null
                        ? charsetProvider.decode(file.absPath().toString(), bytes)
                        : charsetProvider.decode(file.absPath().toString(), bytes, override);
                decodedByRel.put(file.relPath(), decoded);
                textByPath.put(decoded.path(), decoded.text());
            } catch (IllegalArgumentException e) {
                findings.add(Finding.of(DECODE_FAILURE_RULE_ID, FindingLevel.ERROR,
                        "復号に失敗した: " + e.getMessage(),
                        SourcePosition.fileStart(file.relPath())));
            }
        }

        List<CobolSemanticModel> models = new ArrayList<>();
        List<String> analyzed = new ArrayList<>();
        for (LintFile file : files) {
            DecodedSource decoded = decodedByRel.get(file.relPath());
            if (file.kind() != LintKind.COBOL || decoded == null) {
                continue;
            }
            ParseOutcome<CobolSemanticModel> outcome =
                    cobolParser.parse(decoded, options.copybookSearchPaths());
            if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                findings.add(failure.finding());
                continue;
            }
            models.add(outcome.value().orElseThrow());
            analyzed.add(file.relPath());
        }

        List<BmsMapset> mapsets = new ArrayList<>();
        BmsSourceParser bmsParser = new BmsSourceParser();
        for (LintFile file : files) {
            DecodedSource decoded = decodedByRel.get(file.relPath());
            if (file.kind() != LintKind.BMS || decoded == null) {
                continue;
            }
            BmsParseResult parseResult = bmsParser.parse(decoded.text());
            for (BmsParseError error : parseResult.errors()) {
                findings.add(Finding.parseFailure(
                        new SourcePosition(file.relPath(), Math.max(1, error.line()),
                                error.column() + 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        error.message()));
            }
            mapsets.addAll(BmsModelMapper.toEngineApi(parseResult, file.relPath()));
        }

        List<ControlFlowGraph> graphs = models.stream().map(CfgBuilder::build).toList();
        ControlFlowGraphs cfgs = new ControlFlowGraphs(graphs);

        AnalysisContext context = AnalysisContext.of(models, List.of(), List.of(), mapsets,
                Optional.empty(),
                Map.of(SourceTextIndex.class, new SourceTextIndex(textByPath),
                        ControlFlowGraphs.class, cfgs));
        List<Rule> activeRules = Stream.concat(
                        services.rules(AnalysisPhase.SYNTAX).stream(),
                        services.rules(AnalysisPhase.CONTROL_FLOW).stream())
                .filter(rule -> !options.disabledRuleIds().contains(rule.id()))
                .toList();
        for (Rule rule : activeRules) {
            findings.addAll(rule.evaluate(context));
        }

        List<Finding> normalized = new ArrayList<>(findings.stream()
                .map(finding -> relativize(finding, relByAbs, options.copybookSearchPaths()))
                .toList());
        normalized.sort(SarifWriter.findingOrder());

        return new Result(normalized, analyzed, SarifWriter.toJson(activeRules, normalized),
                ExitCodes.fromFindings(normalized));
    }

    private static <T> T first(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }

    /** scan と同じフォルダ規約で、lint対象のCOBOL本体・コピー句・BMSを発見する(相対パスの辞書順)。 */
    private static List<LintFile> discover(Path inputDir) {
        Map<String, LintKind> kindByDir = new LinkedHashMap<>();
        kindByDir.put("bms", LintKind.BMS);
        kindByDir.put("cobol", LintKind.COBOL);
        kindByDir.put("copy", LintKind.COPYBOOK);
        kindByDir.put("copybook", LintKind.COPYBOOK);
        List<LintFile> files = new ArrayList<>();
        for (Map.Entry<String, LintKind> entry : kindByDir.entrySet()) {
            Path dir = inputDir.resolve(entry.getKey());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            String extension = entry.getValue().extension;
            try (Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(extension))
                        .forEach(p -> files.add(new LintFile(
                                entry.getKey() + "/" + p.getFileName(), p, entry.getValue())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(Comparator.comparing(LintFile::relPath));
        return files;
    }

    private static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * finding の位置ファイルを入力フォルダ相対パスへ置き換える。入力フォルダで対応が取れない
     * 絶対パスは --copybook-path の各ディレクトリ基準の相対化も試み、どれにも一致しない場合のみ
     * 絶対パスのまま返す。
     */
    private static Finding relativize(Finding finding, Map<Path, String> relByAbs,
            List<Path> copybookSearchPaths) {
        Path file;
        try {
            file = Path.of(finding.location().file());
        } catch (java.nio.file.InvalidPathException e) {
            return finding;
        }
        if (!file.isAbsolute()) {
            return finding;
        }
        Path normalized = file.normalize();
        String rel = relByAbs.get(normalized);
        if (rel == null) {
            for (Path dir : copybookSearchPaths) {
                Path base = dir.toAbsolutePath().normalize();
                if (normalized.startsWith(base)) {
                    rel = base.relativize(normalized).toString().replace('\\', '/');
                    break;
                }
            }
        }
        if (rel == null) {
            return finding;
        }
        SourcePosition position = finding.location();
        return new Finding(finding.ruleId(), finding.level(), finding.message(),
                new SourcePosition(rel, position.line(), position.column(), position.byteOffset()),
                finding.codeFlows(), finding.fixes());
    }
}
