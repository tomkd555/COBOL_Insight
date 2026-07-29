package jp.cobolinsight.rules.sql;

import jp.cobolinsight.cobolfrontend.Che4zCobolParser;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.source.DecodedSource;
import jp.cobolinsight.engineapi.source.EncodingInfo;
import jp.cobolinsight.engineapi.source.SourcePosition;
import jp.cobolinsight.engineapi.source.SourceRange;
import jp.cobolinsight.engineapi.spi.AnalysisContext;
import jp.cobolinsight.engineapi.spi.ParseOutcome;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;
import jp.cobolinsight.sqlfrontend.JsqlSqlParser;

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
 * SQL助言(Sルール)テストの補助。合成SQL・samples の埋め込みSQLを、本番と同じ SqlParser SPI
 * (sql-frontend の {@link JsqlSqlParser})で {@link SqlStatementModel} へ変換し、それを
 * {@code sqlStatements} に載せた AnalysisContext を組む。sql-frontend が算出した構造シグナルを
 * 消費側ルールへそのまま流す点は、本番経路(ScanRunner.persistSqlStatements)と同じである。
 */
final class SqlAdviceFixtures {

    static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();
    private static final String SYNTHETIC_FILE = "synthetic.cbl";
    private static final JsqlSqlParser SQL_PARSER = new JsqlSqlParser();

    private SqlAdviceFixtures() {
    }

    /** 単一行の合成SQLを、指定行に位置づけた SqlStatementModel へ変換する。 */
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

    /** SqlStatementModel 群を sqlStatements に載せた AnalysisContext。 */
    static AnalysisContext context(SqlStatementModel... statements) {
        return AnalysisContext.of(List.of(), List.of(), List.of(statements), List.of(),
                Optional.empty(), Map.of());
    }

    /** samples/cobol/<name>.cbl を実パーサーで解析し、埋め込みSQLの SqlStatementModel を集める。 */
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

    /** samples の埋め込みSQLを sqlStatements に載せた AnalysisContext。 */
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
