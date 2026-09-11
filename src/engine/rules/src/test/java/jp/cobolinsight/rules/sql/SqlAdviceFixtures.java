package jp.cobolinsight.rules.sql;

import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.sql.SqlStatementModel;
import jp.cobolinsight.core.sql.SqlStructureSignals;
import jp.cobolinsight.frontend.sql.Db2zSqlParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test support for SQL advisory (S-rule) tests. Converts synthetic SQL and the samples'
 * embedded SQL into {@link SqlStatementModel} using the same SqlParser SPI as production
 * (sql-frontend's {@link Db2zSqlParser}), and builds an AnalysisContext carrying them in
 * {@code sqlStatements}. Passing the structural signals computed by sql-frontend straight
 * through to the consuming rule mirrors the production path (Persist.writeSqlStatements).
 */
final class SqlAdviceFixtures {

    static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();
    private static final String SYNTHETIC_FILE = "synthetic.cbl";
    private static final Db2zSqlParser SQL_PARSER = new Db2zSqlParser();

    private SqlAdviceFixtures() {
    }

    /** Converts a single-line synthetic SQL statement into a SqlStatementModel positioned at the given line. */
    static SqlStatementModel model(String sql, int line) {
        SourcePosition start = new SourcePosition(SYNTHETIC_FILE, line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        SourcePosition end = new SourcePosition(SYNTHETIC_FILE, line, 1,
                SourcePosition.UNKNOWN_BYTE_OFFSET);
        EmbeddedBlock block = new EmbeddedBlock(EmbeddedBlockKind.SQL, sql, Map.of(),
                new SourceRange(start, end));
        ParseOutcome<SqlStatementModel> outcome = SQL_PARSER.parse(block);
        return outcome.value().orElseThrow(() -> new AssertionError(
                "合成SQLを解析できない: " + sql + " -> "
                        + outcome.failureFinding().map(Object::toString).orElse("原因不明")));
    }

    /**
     * A degraded statement that nevertheless carries structure signals, so a test can tell a rule
     * that reads {@code isFullyAnalysed()} from one that only ever sees empty signals.
     */
    static SqlStatementModel degradedWithSignals(String sql, int line,
            SqlStructureSignals signals) {
        SqlStatementModel parsed = model(sql, line);
        return new SqlStatementModel(parsed.kind(), parsed.originalText(), parsed.mangledText(),
                parsed.hostVariables(), parsed.referencedTables(), parsed.range(), signals,
                parsed.analysis(), parsed.diagnostic());
    }

    /** An AnalysisContext carrying the given SqlStatementModel instances in sqlStatements. */
    static AnalysisContext context(SqlStatementModel... statements) {
        return AnalysisContext.of(List.of(), List.of(), List.of(statements), List.of(),
                Optional.empty(), Map.of());
    }

    /** Parses samples/cobol/<name>.cbl with the real parser and collects the SqlStatementModel of its embedded SQL. */
    static List<SqlStatementModel> samplesSqlModels(String cobolBaseName) {
        Path file = SAMPLES.resolve("cobol").resolve(cobolBaseName);
        String text = readText(file);
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                .parse(decoded(file.toString(), text), List.of(SAMPLES.resolve("copybook")));
        CobolSemanticModel model = outcome.value().orElseThrow(() -> new AssertionError(
                cobolBaseName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
        List<SqlStatementModel> statements = new ArrayList<>();
        for (EmbeddedBlock block : model.embeddedBlocks()) {
            if (block.kind() != EmbeddedBlockKind.SQL) {
                continue;
            }
            SQL_PARSER.parse(block).value().ifPresent(statements::add);
        }
        return statements;
    }

    /** Writes a synthetic fixture text to a file and parses it with the real parser. */
    static CobolSemanticModel parse(Path dir, String fileName, String text, Path... copybookDirs) {
        Path file = dir.resolve(fileName);
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                .parse(decoded(file.toString(), text), List.of(copybookDirs));
        return outcome.value().orElseThrow(() -> new AssertionError(
                fileName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
    }

    /** An AnalysisContext carrying whole programs, with their embedded SQL in sqlStatements. */
    static AnalysisContext programContext(CobolSemanticModel... models) {
        List<SqlStatementModel> statements = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() == EmbeddedBlockKind.SQL) {
                    SQL_PARSER.parse(block).value().ifPresent(statements::add);
                }
            }
        }
        return AnalysisContext.of(List.of(models), List.of(), statements, List.of(),
                Optional.empty(), Map.of());
    }

    /**
     * The same, with a control flow graph per program. R055 reads the graph to place each cursor
     * statement, so its tests need the whole program rather than a statement on its own.
     */
    static AnalysisContext cfgProgramContext(CobolSemanticModel... models) {
        List<SqlStatementModel> statements = new ArrayList<>();
        List<ControlFlowGraph> graphs = new ArrayList<>();
        for (CobolSemanticModel model : models) {
            for (EmbeddedBlock block : model.embeddedBlocks()) {
                if (block.kind() == EmbeddedBlockKind.SQL) {
                    SQL_PARSER.parse(block).value().ifPresent(statements::add);
                }
            }
            graphs.add(CfgBuilder.build(model));
        }
        return AnalysisContext.of(List.of(models), List.of(), statements, List.of(),
                Optional.empty(), Map.of(ControlFlowGraphs.class, new ControlFlowGraphs(graphs)));
    }

    /** The program samples/cobol/&lt;name&gt;.cbl as the real parser reads it. */
    static CobolSemanticModel samplesProgram(String cobolBaseName) {
        Path file = SAMPLES.resolve("cobol").resolve(cobolBaseName);
        String text = readText(file);
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser()
                .parse(decoded(file.toString(), text), List.of(SAMPLES.resolve("copybook")));
        return outcome.value().orElseThrow(() -> new AssertionError(
                cobolBaseName + " のパースが失敗した: " + outcome.failureFinding().orElse(null)));
    }

    /** An AnalysisContext carrying the samples' embedded SQL in sqlStatements. */
    static AnalysisContext samplesContext(String cobolBaseName) {
        return AnalysisContext.of(List.of(), List.of(), samplesSqlModels(cobolBaseName),
                List.of(), Optional.empty(), Map.of());
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
