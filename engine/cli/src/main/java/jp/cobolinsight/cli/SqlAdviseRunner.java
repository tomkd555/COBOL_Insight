package jp.cobolinsight.cli;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.json.JsonWriter;
import jp.cobolinsight.engineapi.pipeline.AnalysisServices;
import jp.cobolinsight.engineapi.pipeline.ExitCodes;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.AnalysisPhase;
import jp.cobolinsight.engineapi.spi.CharsetProvider;
import jp.cobolinsight.engineapi.spi.CobolParser;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.spi.Rule;
import jp.cobolinsight.engineapi.spi.SqlParser;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;
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
 * `sql-advise` の中核処理。資産フォルダのCOBOLを復号・パースし、埋め込みSQLを SqlParser SPI で
 * SQL文モデルへ写像して {@link AnalysisContext#sqlStatements()} に供給したうえで、SQL助言
 * (id が "S" で始まる構文段階のルール)のみを実行して findings を返す(裁定A5・A6)。復号失敗・
 * COBOLパース失敗も errorレベルの finding として合流させる。出力は決定論とする: findings は
 * (ファイル・行・桁・ルールID・メッセージ)の昇順に正規化し、位置のファイルは入力フォルダからの
 * 相対パスへ揃える。
 */
public final class SqlAdviseRunner {

    private static final String DECODE_FAILURE_RULE_ID = "decode-failure";
    private static final String COBOL_EXTENSION = ".cbl";

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

    private record CobolFile(String relPath, Path absPath) {
    }

    private SqlAdviseRunner() {
    }

    public static Result run(Options options) {
        AnalysisServices services = AnalysisServices.load();
        CharsetProvider charsetProvider =
                first(services.charsetProviders(), "CharsetProvider");
        CobolParser cobolParser = first(services.cobolParsers(), "CobolParser");
        SqlParser sqlParser = first(services.sqlParsers(), "SqlParser");

        List<CobolFile> files = discover(options.inputDir());
        List<Finding> findings = new ArrayList<>();
        Map<Path, String> relByAbs = new HashMap<>();
        Map<String, DecodedSource> decodedByRel = new LinkedHashMap<>();

        for (CobolFile file : files) {
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
            } catch (IllegalArgumentException e) {
                findings.add(Finding.of(DECODE_FAILURE_RULE_ID, FindingLevel.ERROR,
                        "復号に失敗した: " + e.getMessage(),
                        SourcePosition.fileStart(file.relPath())));
            }
        }

        List<CobolSemanticModel> models = new ArrayList<>();
        List<SqlStatementModel> sqlStatements = new ArrayList<>();
        List<String> analyzed = new ArrayList<>();
        for (CobolFile file : files) {
            DecodedSource decoded = decodedByRel.get(file.relPath());
            if (decoded == null) {
                continue;
            }
            ParseOutcome<CobolSemanticModel> outcome =
                    cobolParser.parse(decoded, options.copybookSearchPaths());
            if (outcome instanceof ParseOutcome.Failure<CobolSemanticModel> failure) {
                findings.add(failure.finding());
                continue;
            }
            CobolSemanticModel model = outcome.value().orElseThrow();
            models.add(model);
            analyzed.add(file.relPath());
            collectSqlStatements(model, sqlParser, sqlStatements);
        }

        AnalysisContext context = AnalysisContext.of(models, List.of(), sqlStatements, List.of(),
                Optional.empty(), Map.of());
        // sql-advise は SQL助言(id が "S")のみを実行し、バグ検出(id が "R")は lint へ分離する(裁定A5)。
        List<Rule> activeRules = services.rules(AnalysisPhase.SYNTAX).stream()
                .filter(rule -> rule.id().startsWith("S"))
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

    /**
     * 埋め込みSQLブロックを SqlParser SPI で SQL文モデルへ写像して集める
     * ({@code ScanRunner.persistSqlStatements} と同型)。解析できないブロックは助言対象から外す。
     */
    private static void collectSqlStatements(CobolSemanticModel model, SqlParser sqlParser,
            List<SqlStatementModel> sqlStatements) {
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.SQL) {
                continue;
            }
            sqlParser.parse(block).value().ifPresent(sqlStatements::add);
        }
    }

    private static <T> T first(List<T> implementations, String contractName) {
        if (implementations.isEmpty()) {
            throw new IllegalStateException(contractName + " の実装が実行時クラスパスに無い");
        }
        return implementations.get(0);
    }

    /** scan と同じフォルダ規約で、cobol ディレクトリ配下のCOBOL本体を発見する(相対パスの辞書順)。 */
    private static List<CobolFile> discover(Path inputDir) {
        Path dir = inputDir.resolve("cobol");
        List<CobolFile> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> children = Files.list(dir)) {
                children.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                                .endsWith(COBOL_EXTENSION))
                        .forEach(p -> files.add(
                                new CobolFile("cobol/" + p.getFileName(), p)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        files.sort(Comparator.comparing(CobolFile::relPath));
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
     * 絶対パスのまま返す(LintRunner と同一規約)。
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
