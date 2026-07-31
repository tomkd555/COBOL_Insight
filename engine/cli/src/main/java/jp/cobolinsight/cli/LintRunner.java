package jp.cobolinsight.cli;

import jp.cobolinsight.bmsfrontend.BmsModelMapper;
import jp.cobolinsight.bmsfrontend.BmsParseError;
import jp.cobolinsight.bmsfrontend.BmsParseResult;
import jp.cobolinsight.bmsfrontend.BmsSourceParser;
import jp.cobolinsight.dataflow.CfgBuilder;
import jp.cobolinsight.dataflow.DataFlowEngine;
import jp.cobolinsight.engineapi.bms.BmsMapset;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraph;
import jp.cobolinsight.engineapi.cfg.ControlFlowGraphs;
import jp.cobolinsight.engineapi.dataflow.DataFlowFacts;
import jp.cobolinsight.engineapi.finding.CodeFlow;
import jp.cobolinsight.engineapi.finding.CodeFlowStep;
import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.finding.FixSuggestion;
import jp.cobolinsight.engineapi.finding.TextEdit;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.FixProducer;
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
 * 制御フローグラフを構築し、BMSをマップモデルへ変換したうえで、構文段階(AnalysisPhase.SYNTAX)と
 * 制御フロー段階(AnalysisPhase.CONTROL_FLOW)のルールを実行して findings を返す。復号失敗・
 * パース失敗も errorレベルの finding として合流させる。FixProducer を持つルールの検出には
 * 修正案(SARIF の fixes)を付す。出力は決定論とする: findings は
 * (ファイル・行・桁・ルールID・メッセージ)の昇順に正規化し、位置・汚染経路の各歩・修正案の
 * 編集範囲のファイルは入力フォルダからの相対パスへ揃える。
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

    /** lint対象の種別。COBOLはパース、COPYBOOKはテキスト索引のみ、BMSはマップモデルへ変換する。 */
    private enum LintKind {
        COBOL, COPYBOOK, BMS
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
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file.absPath());
            } catch (IOException e) {
                // 読み取れない1ファイルで解析全体を止めない。対象から外し、標準エラーで伝える。
                System.err.println("警告: 読み取れないため対象から外す: " + file.absPath()
                        + " (" + e + ")");
                continue;
            }
            relByAbs.put(file.absPath().toAbsolutePath().normalize(), file.relPath());
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
                // BmsParseError の桁は0起点、SourcePosition の桁は1起点のため1加える。
                findings.add(Finding.parseFailure(
                        new SourcePosition(file.relPath(), Math.max(1, error.line()),
                                error.column() + 1, SourcePosition.UNKNOWN_BYTE_OFFSET),
                        error.message()));
            }
            mapsets.addAll(BmsModelMapper.toEngineApi(parseResult, file.relPath()));
        }

        List<ControlFlowGraph> graphs = models.stream().map(CfgBuilder::build).toList();
        ControlFlowGraphs cfgs = new ControlFlowGraphs(graphs);
        DataFlowFacts dataFlowFacts = DataFlowEngine.analyzeAll(models, cfgs);

        AnalysisContext context = AnalysisContext.of(models, List.of(), List.of(), mapsets,
                Optional.empty(),
                Map.of(SourceTextIndex.class, new SourceTextIndex(textByPath),
                        ControlFlowGraphs.class, cfgs,
                        DataFlowFacts.class, dataFlowFacts));
        // lint は構文・制御フロー・データフローの3段階のバグ検出ルール(id が "R")のみを実行し、
        // SQL 助言(id が "S")は sql-advise サブコマンドが担う。
        List<Rule> activeRules = Stream.of(
                        services.rules(AnalysisPhase.SYNTAX),
                        services.rules(AnalysisPhase.CONTROL_FLOW),
                        services.rules(AnalysisPhase.DATA_FLOW))
                .flatMap(List::stream)
                .filter(rule -> rule.id().startsWith("R"))
                .filter(rule -> !options.disabledRuleIds().contains(rule.id()))
                .toList();
        for (Rule rule : activeRules) {
            FixProducer producer = rule.fixProducer().orElse(null);
            for (Finding finding : rule.evaluate(context)) {
                findings.add(producer == null ? finding : withFix(finding, producer, context));
            }
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

    /** scan と同じ走査で、lint対象のCOBOL本体・コピー句・BMSを発見する(相対パスの辞書順)。JCLは対象外。 */
    private static List<LintFile> discover(Path inputDir) {
        List<LintFile> files = new ArrayList<>();
        for (SourceDiscovery.DiscoveredFile file : SourceDiscovery.discover(inputDir).files()) {
            LintKind kind = toLintKind(file.kind());
            if (kind != null) {
                files.add(new LintFile(file.relPath(), file.absPath(), kind));
            }
        }
        files.sort(Comparator.comparing(LintFile::relPath));
        return files;
    }

    /** lint が扱わない種別(JCL)には null を返す。 */
    private static LintKind toLintKind(SourceDiscovery.Kind kind) {
        return switch (kind) {
            case BMS -> LintKind.BMS;
            case COBOL -> LintKind.COBOL;
            case COPYBOOK -> LintKind.COPYBOOK;
            case JCL -> null;
        };
    }


    /**
     * ルールの FixProducer が修正案を返した finding へ fixes を付す。修正案を返さない finding は
     * そのまま返す。位置は意味モデルの sourceFile(絶対パス)のままで、相対化は
     * {@link #relativize} が finding と編集範囲へ同時に施す。
     */
    private static Finding withFix(Finding finding, FixProducer producer,
            AnalysisContext context) {
        return producer.produce(finding, context)
                .map(fix -> new Finding(finding.ruleId(), finding.level(), finding.message(),
                        finding.location(), finding.codeFlows(), List.of(fix)))
                .orElse(finding);
    }

    /**
     * finding の位置と、汚染経路の各歩・修正案の各編集範囲を、いずれも入力フォルダ相対パスへ
     * 揃える。
     */
    private static Finding relativize(Finding finding, Map<Path, String> relByAbs,
            List<Path> copybookSearchPaths) {
        List<CodeFlow> codeFlows = new ArrayList<>();
        for (CodeFlow codeFlow : finding.codeFlows()) {
            List<CodeFlowStep> steps = new ArrayList<>();
            for (CodeFlowStep step : codeFlow.steps()) {
                steps.add(new CodeFlowStep(
                        relativize(step.position(), relByAbs, copybookSearchPaths),
                        step.message()));
            }
            codeFlows.add(new CodeFlow(steps));
        }
        List<FixSuggestion> fixes = new ArrayList<>();
        for (FixSuggestion fix : finding.fixes()) {
            List<TextEdit> edits = new ArrayList<>();
            for (TextEdit edit : fix.edits()) {
                edits.add(new TextEdit(new SourceRange(
                        relativize(edit.range().start(), relByAbs, copybookSearchPaths),
                        relativize(edit.range().end(), relByAbs, copybookSearchPaths)),
                        edit.replacement()));
            }
            fixes.add(new FixSuggestion(fix.description(), edits));
        }
        return new Finding(finding.ruleId(), finding.level(), finding.message(),
                relativize(finding.location(), relByAbs, copybookSearchPaths),
                codeFlows, fixes);
    }

    /**
     * 位置のファイルを入力フォルダ相対パスへ置き換える。入力フォルダで対応が取れない絶対パスは
     * --copybook-path の各ディレクトリ基準の相対化も試み、どれにも一致しない場合のみ絶対パスの
     * まま返す。
     */
    private static SourcePosition relativize(SourcePosition position, Map<Path, String> relByAbs,
            List<Path> copybookSearchPaths) {
        Path file;
        try {
            file = Path.of(position.file());
        } catch (java.nio.file.InvalidPathException e) {
            return position;
        }
        if (!file.isAbsolute()) {
            return position;
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
            return position;
        }
        return new SourcePosition(rel, position.line(), position.column(), position.byteOffset());
    }
}
