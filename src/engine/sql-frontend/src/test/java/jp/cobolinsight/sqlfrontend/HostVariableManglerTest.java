package jp.cobolinsight.sqlfrontend;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostVariableManglerTest {

    private final HostVariableMangler mangler = new HostVariableMangler();

    private MangledSql mangled(String sql) {
        MangleResult result = mangler.mangle(sql);
        assertInstanceOf(MangleResult.Mangled.class, result, "マングリングに成功する");
        return ((MangleResult.Mangled) result).sql();
    }

    @Test
    void ハイフン入りホスト変数を連番トークンへ変換して復元する() {
        String sql = "SELECT C1 FROM T1 WHERE CUST_ID = :WS-CUST-ID";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C1 FROM T1 WHERE CUST_ID = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals(1, result.hostVariables().size());
        HostVariableReference ref = result.hostVariables().get(0);
        assertEquals("HV1", ref.token());
        assertEquals("WS-CUST-ID", ref.dataName());
        assertNull(ref.indicatorName());
    }

    @Test
    void 日本語ホスト変数を変換して復元する() {
        String sql = "UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = ZAIKO_SU + :HOST-増減数量"
                + " WHERE SHOHIN_CD = :HOST-商品コード";
        MangledSql result = mangled(sql);
        assertFalse(result.sql().contains("HOST-"));
        assertEquals("UPDATE SYKDB.ZAIKOM SET ZAIKO_SU = ZAIKO_SU + :HV1"
                + " WHERE SHOHIN_CD = :HV2", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("HOST-増減数量", result.hostVariables().get(0).dataName());
        assertEquals("HOST-商品コード", result.hostVariables().get(1).dataName());
    }

    @Test
    void 指示変数つきホスト変数を単一トークンへまとめて分解し復元する() {
        String sql = "VALUES (:WS-CUST-ID:WS-CUST-IND)";
        MangledSql result = mangled(sql);
        assertEquals("VALUES (:HV1)", result.sql());
        assertEquals(sql, result.restore());
        HostVariableReference ref = result.hostVariables().get(0);
        assertEquals("WS-CUST-ID", ref.dataName());
        assertEquals("WS-CUST-IND", ref.indicatorName());
        assertEquals(":WS-CUST-ID:WS-CUST-IND", ref.originalText());
    }

    @Test
    void 添字付きホスト変数を変換して復元する() {
        String sql = "SELECT C1 FROM T1 WHERE A = :WS-TABLE(1) AND B = :WS-A(:WS-I)";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C1 FROM T1 WHERE A = :HV1(1) AND B = :HV2(:HV3)", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("WS-TABLE", result.hostVariables().get(0).dataName());
        assertEquals("WS-A", result.hostVariables().get(1).dataName());
        assertEquals("WS-I", result.hostVariables().get(2).dataName());
    }

    @Test
    void 修飾付きホスト変数を変換して復元する() {
        String sql = "SELECT C1 FROM T1 WHERE K = :A OF B";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C1 FROM T1 WHERE K = :HV1 OF B", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("A", result.hostVariables().get(0).dataName());
    }

    @Test
    void 同一ホスト変数の再出現には同じトークンを割り当てる() {
        String sql = "WHERE A = :WS-K AND B = :WS-K AND C = :WS-L";
        MangledSql result = mangled(sql);
        assertEquals("WHERE A = :HV1 AND B = :HV1 AND C = :HV2", result.sql());
        assertEquals(sql, result.restore());
        assertEquals(2, result.hostVariables().size());
    }

    @Test
    void 文字列リテラル内のコロンは変換しない() {
        String sql = "SELECT ':NOT-HOST' FROM T WHERE A = :WS-A";
        MangledSql result = mangled(sql);
        assertEquals("SELECT ':NOT-HOST' FROM T WHERE A = :HV1", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void マングリング済みSQLはJSqlParserで解析できる() {
        String sql = "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM"
                + " WHERE SHOHIN_CD = :HOST-商品コード AND SOKO_CD = :HOST-倉庫コード";
        MangledSql result = mangled(sql);
        assertDoesNotThrow(() -> CCJSqlParserUtil.parse(result.sql()));
        assertEquals(sql, result.restore());
    }

    @Test
    void 名前が続かないコロンは解析対象外として報告する() {
        MangleResult result = mangler.mangle("SELECT : FROM T");
        MangleResult.NotAnalyzable na = assertInstanceOf(MangleResult.NotAnalyzable.class, result);
        assertFalse(na.reason().isBlank());
    }

    @Test
    void ハイフンで終わる名前は解析対象外として報告する() {
        MangleResult result = mangler.mangle("WHERE A = :WS-CUST-");
        assertInstanceOf(MangleResult.NotAnalyzable.class, result);
    }

    @Test
    void 数字だけの名前は解析対象外として報告する() {
        MangleResult result = mangler.mangle("WHERE A = :123");
        assertInstanceOf(MangleResult.NotAnalyzable.class, result);
    }

    @Test
    void 指示変数が不正なら解析対象外として報告する() {
        MangleResult result = mangler.mangle("VALUES (:WS-A:9)");
        assertInstanceOf(MangleResult.NotAnalyzable.class, result);
    }
}
