package jp.cobolinsight.rules.sql;

import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
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
 * through to the consuming rule mirrors the production path (ScanRunner.persistSqlStatements).
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
