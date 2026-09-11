package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlAnalysis;
import jp.cobolinsight.core.sql.SqlStatementKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlStatementAnalyzerTest {

    private final SqlStatementAnalyzer analyzer = new SqlStatementAnalyzer();
    private final SqlBlockExtractor extractor = new SqlBlockExtractor();

    private static SqlBlock block(String sqlText, SqlBlockKind kind) {
        return new SqlBlock(sqlText, kind, new SourcePosition(1, 12), new SourcePosition(1, 20));
    }

    private static String readSample(String fileName) throws IOException {
        return Files.readString(
                Path.of("..", "..", "..", "samples", "cobol", fileName), StandardCharsets.UTF_8);
    }

    private static Set<String> dataNames(SqlAnalysisResult result) {
        Set<String> names = new TreeSet<>();
        result.hostVariables().forEach(ref -> names.add(ref.dataName()));
        return names;
    }

    @Test
    void selectInto文の種別と表とinto先を得る() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "SELECT ZAIKO_SU INTO :HOST-在庫数量\nFROM SYKDB.ZAIKOM\n"
                + "WHERE SHOHIN_CD = :HOST-商品コード\nAND SOKO_CD = :HOST-倉庫コード",
                SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.SELECT_INTO, result.statementKind());
        assertEquals(Set.of("SYKDB.ZAIKOM"), Set.copyOf(result.tableNames()));
        assertEquals(List.of("HOST-在庫数量"), result.intoTargets());
        assertEquals(Set.of("HOST-在庫数量", "HOST-商品コード", "HOST-倉庫コード"), dataNames(result));
        assertNull(result.cursorName());
    }

    @Test
    void update文の種別と表を得る() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "UPDATE SYKDB.ZAIKOM\nSET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量\n"
                + "WHERE SHOHIN_CD = :HOST-商品コード\nAND SOKO_CD = :HOST-倉庫コード",
                SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.UPDATE, result.statementKind());
        assertEquals(Set.of("SYKDB.ZAIKOM"), Set.copyOf(result.tableNames()));
        assertEquals(Set.of("HOST-増減数量", "HOST-商品コード", "HOST-倉庫コード"), dataNames(result));
    }

    @Test
    void insert文の種別と表を得る() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "INSERT INTO SYKDB.ZAIKOM\n(SHOHIN_CD, SOKO_CD, ZAIKO_SU,\nHIKIATE_SU, KOSHIN_BI)\n"
                + "VALUES (:HOST-商品コード, :HOST-倉庫コード,\n:HOST-増減数量, 0, '00000000')",
                SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.INSERT, result.statementKind());
        assertEquals(Set.of("SYKDB.ZAIKOM"), Set.copyOf(result.tableNames()));
        assertEquals(Set.of("HOST-増減数量", "HOST-商品コード", "HOST-倉庫コード"), dataNames(result));
    }

    @Test
    void declareCursor文のカーソル名と表を得る() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "DECLARE SYKZAIKOCUR CURSOR FOR\n"
                + "SELECT SHOHIN_CD, SOKO_CD, ZAIKO_SU, HIKIATE_SU\nFROM SYKDB.ZAIKOM",
                SqlBlockKind.DECLARE_CURSOR));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.DECLARE_CURSOR, result.statementKind());
        assertEquals("SYKZAIKOCUR", result.cursorName());
        assertEquals(Set.of("SYKDB.ZAIKOM"), Set.copyOf(result.tableNames()));
    }

    @Test
    void open文とclose文のカーソル名を得る() {
        SqlAnalysisResult open = analyzer.analyze(block("OPEN SYKZAIKOCUR", SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, open.analysis());
        assertEquals(SqlStatementKind.OPEN, open.statementKind());
        assertEquals("SYKZAIKOCUR", open.cursorName());

        SqlAnalysisResult close = analyzer.analyze(block("CLOSE SYKZAIKOCUR", SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, close.analysis());
        assertEquals(SqlStatementKind.CLOSE, close.statementKind());
        assertEquals("SYKZAIKOCUR", close.cursorName());
    }

    @Test
    void fetch文のカーソル名とinto先を得る() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "FETCH SYKZAIKOCUR\nINTO :HOST-商品コード, :HOST-倉庫コード,\n"
                + ":HOST-在庫数量, :HOST-引当数量",
                SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.FETCH, result.statementKind());
        assertEquals("SYKZAIKOCUR", result.cursorName());
        assertEquals(List.of("HOST-商品コード", "HOST-倉庫コード", "HOST-在庫数量", "HOST-引当数量"),
                result.intoTargets());
    }

    @Test
    void マングリング不能な文は劣化解析として報告する() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "SELECT A FROM T WHERE B = :WS-CUST-", SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.DEGRADED, result.analysis());
        assertNotNull(result.diagnostic());
        assertEquals(SqlStatementKind.SELECT, result.statementKind());
        assertEquals(List.of("T"), result.tableNames());
    }

    @Test
    void 文法が構文エラーとする文は劣化解析として報告する() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "SELECT * FROM", SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.DEGRADED, result.analysis());
        assertNotNull(result.diagnostic());
        assertEquals(SqlStatementKind.SELECT, result.statementKind());
    }

    @Test
    void syk006の実ファイルの全ブロックを解析できる() throws IOException {
        List<SqlBlock> blocks = extractor.extract(readSample("SYK006.cbl"));
        Set<String> tables = new TreeSet<>();
        for (SqlBlock b : blocks) {
            SqlAnalysisResult result = analyzer.analyze(b);
            assertEquals(SqlAnalysis.FULL, result.analysis(),
                    "解析対象外にならない: " + b.sqlText());
            tables.addAll(result.tableNames());
            if (result.statementKind() == SqlStatementKind.DECLARE_CURSOR) {
                assertEquals("SYKZAIKOCUR", result.cursorName());
            }
        }
        assertEquals(Set.of("SYKDB.ZAIKOM"), tables);
    }

    @Test
    void syk007の実ファイルの全ブロックを解析し参照表を集約できる() throws IOException {
        List<SqlBlock> blocks = extractor.extract(readSample("SYK007.cbl"));
        Set<String> tables = new TreeSet<>();
        for (SqlBlock b : blocks) {
            SqlAnalysisResult result = analyzer.analyze(b);
            assertEquals(SqlAnalysis.FULL, result.analysis());
            tables.addAll(result.tableNames());
        }
        assertEquals(Set.of("SYKDB.SOKOM", "SYKDB.ZAIKOM"), tables);
    }

    @Test
    void 行コメントがあっても後続の本文を解析する() {
        SqlAnalysisResult result = analyzer.analyze(block(
                "SELECT A INTO :WK-A FROM T -- don't touch\n"
                + "WHERE UPPER(K) = :WK-K",
                SqlBlockKind.EXECUTABLE));
        assertEquals(SqlAnalysis.FULL, result.analysis());
        assertEquals(SqlStatementKind.SELECT_INTO, result.statementKind());
        assertEquals(Set.of("WK-A", "WK-K"), dataNames(result),
                "コメント内のアポストロフィを文字列リテラルの開始とみなさないこと");
        assertEquals(List.of("UPPER(K) = :WK-K"),
                result.structureSignals().functionOnColumnPredicates(),
                "行コメントより後ろの述語も解析対象に含めること");
    }
}
