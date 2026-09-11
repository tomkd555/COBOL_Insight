package jp.cobolinsight.frontend.sql;

import jp.cobolinsight.core.sql.SqlAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostVariableManglerTest {

    private final HostVariableMangler mangler = new HostVariableMangler();

    private MangledSql mangled(String sql) {
        MangledSql result = mangler.mangle(sql);
        assertEquals(List.of(), result.diagnostics(), "マングリングに成功する");
        return result;
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
    void 日本語の表名と列名を連番トークンへ変換して復元する() {
        String sql = "SELECT 数量 FROM 在庫マスタ WHERE 商品CD = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT NJ1_ FROM NJ2_ WHERE NJ3_ = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("在庫マスタ", result.nonAsciiByToken().get("NJ2_"));
        assertEquals("商品CD", result.nonAsciiByToken().get("NJ3_"));
    }

    @Test
    void 日本語の区切り識別子も変換して復元する() {
        String sql = "SELECT \"数量\" FROM \"在庫\"";
        MangledSql result = mangled(sql);
        assertEquals("SELECT \"NJ1_\" FROM \"NJ2_\"", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 日本語の名前を含む文はDb2z文法で解析できる() {
        SqlAnalysisResult analyzed = new SqlStatementAnalyzer().analyze(
                new SqlBlock("SELECT 数量 FROM 在庫マスタ WHERE 商品CD = :WS-CD",
                        SqlBlockKind.EXECUTABLE,
                        new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(SqlAnalysis.FULL, analyzed.analysis(), analyzed.diagnostic());
        assertEquals(List.of("在庫マスタ"), analyzed.tableNames());
    }

    @Test
    void 日本語の名前は数字で終わっても1つのトークンになる() {
        String sql = "SELECT C FROM 在庫1 WHERE K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C FROM NJ1_ WHERE K = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("在庫1", result.nonAsciiByToken().get("NJ1_"));
    }

    @Test
    void 日本語と英数字が交互に並ぶ名前も1つのトークンになる() {
        String sql = "SELECT C FROM 商品CD区分 WHERE K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C FROM NJ1_ WHERE K = :HV1", result.sql());
        assertEquals("商品CD区分", result.nonAsciiByToken().get("NJ1_"));
        assertEquals(sql, result.restore());
    }

    /** A hyphen is the minus of SQL; folding it into the token would hide the arithmetic. */
    @Test
    void ハイフンは日本語の名前に続けて1つのトークンにしない() {
        String sql = "UPDATE SYKDB.ZAIKOM SET 在庫数 = 在庫数-1";
        MangledSql result = mangled(sql);
        assertEquals("UPDATE SYKDB.ZAIKOM SET NJ1_ = NJ1_-1", result.sql());
        assertEquals("在庫数", result.nonAsciiByToken().get("NJ1_"));
        assertEquals(sql, result.restore());
    }

    /** The hyphen inside a delimited identifier still comes back where it stood. */
    @Test
    void 区切り識別子の中のハイフンも復元できる() {
        String sql = "SELECT C FROM \"在庫-数\"";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C FROM \"NJ1_-NJ2_\"", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 英数字で始まる日本語の名前も1つのトークンになる() {
        String sql = "SELECT C FROM CD商品 WHERE K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C FROM NJ1_ WHERE K = :HV1", result.sql());
        assertEquals("CD商品", result.nonAsciiByToken().get("NJ1_"));
        assertEquals(sql, result.restore());
    }

    @Test
    void 全角空白が続くホスト変数もトークンの範囲を取り違えない() {
        // The grammar has no rule for a full-width space, so the statement itself goes to the
        // degraded path. What the mangler must still get right is the extent of the token: the
        // name stops at the space, and the space comes back as it was written.
        String sql = "SELECT C FROM T WHERE K = :WS-CUST-ID　AND A = 1";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C FROM T WHERE K = :HV1　AND A = 1", result.sql());
        assertEquals("WS-CUST-ID", result.hostVariables().get(0).dataName());
        assertEquals(sql, result.restore());

        SqlAnalysisResult analyzed = new SqlStatementAnalyzer().analyze(
                new SqlBlock(sql, SqlBlockKind.EXECUTABLE,
                        new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(SqlAnalysis.DEGRADED, analyzed.analysis());
        assertEquals(List.of("T"), analyzed.tableNames(), "the table survives the degraded scan");
    }

    @Test
    void 日本語を含む名前の文はどれもDb2z文法で解析できる() {
        for (String sql : List.of("SELECT C FROM 商品CD区分 WHERE K = 1",
                "SELECT C FROM CD商品 WHERE K = 1",
                "SELECT C FROM 在庫1 WHERE K = 1")) {
            SqlAnalysisResult analyzed = new SqlStatementAnalyzer().analyze(
                    new SqlBlock(sql, SqlBlockKind.EXECUTABLE,
                            new SourcePosition(1, 12), new SourcePosition(1, 20)));
            assertEquals(SqlAnalysis.FULL, analyzed.analysis(), sql + " -> " + analyzed.diagnostic());
        }
    }

    @Test
    void 読み取れないコロンの後ろの日本語も復元する() {
        String sql = "WHERE A = :-商品";
        MangledSql result = mangler.mangle(sql);
        assertEquals(1, result.diagnostics().size(), () -> result.diagnostics().toString());
        // The hyphen stays where it stands: a run of identifier characters does not hold one.
        assertEquals("WHERE A = :-NJ1_", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 区切り識別子の中のアポストロフィは文字定数を開かない() {
        String sql = "SELECT \"IT'S\" FROM T WHERE K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT \"IT'S\" FROM T WHERE K = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("WS-CD", result.hostVariables().get(0).dataName());
    }

    @Test
    void 原文にあるNJ名は置き換え先の候補から外す() {
        String sql = "SELECT NJ1_ FROM 在庫 WHERE NJ2 = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT NJ1_ FROM NJ2_ WHERE NJ2 = :HV1", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 原文にあるHV名は置き換え先の候補から外す() {
        String sql = "WHERE A = :WS-K AND B = :HV1-";
        MangledSql result = mangler.mangle(sql);
        assertEquals("WHERE A = :HV2 AND B = :HV1-", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 劣化解析へ回る文でも復元は原文どおりになる() {
        for (String sql : List.of("SELECT : FROM T", "WHERE A = :WS-CUST-", "WHERE A = :123",
                "VALUES (:WS-A:9)")) {
            MangledSql result = mangler.mangle(sql);
            assertFalse(result.diagnostics().isEmpty(), sql);
            assertEquals(sql, result.restore(), sql);
        }
    }

    @Test
    void 区切り識別子の中のコロンはホスト変数と読まない() {
        String sql = "SELECT \"COL:1\" FROM \"A:B\" WHERE K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("SELECT \"COL:1\" FROM \"A:B\" WHERE K = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals(1, result.hostVariables().size());
    }

    @Test
    void 行をまたぐ修飾は名前の空白を1つに詰める() {
        String sql = "WHERE K = :A\n          OF B";
        MangledSql result = mangled(sql);
        assertEquals("A OF B", result.hostVariables().get(0).dataName());
        assertEquals(sql, result.restore(), "復元は原文どおりであること");
    }

    @Test
    void OF修飾は何段でも名前に畳み込む() {
        MangledSql result = mangled("WHERE K = :A OF B OF C");
        assertEquals("WHERE K = :HV1", result.sql());
        assertEquals("A OF B OF C", result.hostVariables().get(0).dataName());
    }

    @Test
    void OF修飾の添字はホスト変数として別に読む() {
        String sql = "WHERE K = :H OF G(:I)";
        MangledSql result = mangled(sql);
        assertEquals("WHERE K = :HV1(:HV2)", result.sql());
        assertEquals("H OF G", result.hostVariables().get(0).dataName());
        assertEquals("I", result.hostVariables().get(1).dataName());
        assertEquals(sql, result.restore());
    }

    @Test
    void IN述語の並びは1つずつホスト変数として読む() {
        String sql = "WHERE :H IN (:A, :B)";
        MangledSql result = mangled(sql);
        assertEquals("WHERE :HV1 IN (:HV2, :HV3)", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void CAST式の中のホスト変数も変換する() {
        String sql = "SELECT CAST(:H AS INTEGER) FROM T";
        MangledSql result = mangled(sql);
        assertEquals("SELECT CAST(:HV1 AS INTEGER) FROM T", result.sql());
        assertEquals("H", result.hostVariables().get(0).dataName());
        assertEquals(sql, result.restore());
    }

    @Test
    void 名前の続かないコロンが並んでも原文どおり復元する() {
        MangledSql result = mangler.mangle("SELECT A::B FROM T");
        assertEquals(1, result.diagnostics().size(), () -> result.diagnostics().toString());
        assertEquals("SELECT A::HV1 FROM T", result.sql(), "2つめのコロンはホスト変数として読む");
        assertEquals("SELECT A::B FROM T", result.restore());
    }

    @Test
    void 時刻の文字定数はホスト変数と読まない() {
        String sql = "WHERE T = '12:30:00' AND K = :WS-CD";
        MangledSql result = mangled(sql);
        assertEquals("WHERE T = '12:30:00' AND K = :HV1", result.sql());
        assertEquals(sql, result.restore());
    }

    @Test
    void 図形定数と16進定数の中身は変換しない() {
        String sql = "VALUES (G'在庫', N'倉庫', X'0A', :WS-CD)";
        MangledSql result = mangled(sql);
        assertEquals("VALUES (G'在庫', N'倉庫', X'0A', :HV1)", result.sql());
        assertEquals(sql, result.restore());
        assertTrue(result.nonAsciiByToken().isEmpty());
    }

    @Test
    void 文字定数の中の日本語は変換しない() {
        String sql = "SELECT C1 FROM T WHERE KBN = '在庫'";
        MangledSql result = mangled(sql);
        assertEquals(sql, result.sql());
        assertEquals(sql, result.restore());
        assertTrue(result.nonAsciiByToken().isEmpty());
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
    void 空白で区切った指示変数も単一トークンへまとめる() {
        String sql = "VALUES (:WS-CUST-ID :WS-CUST-IND)";
        MangledSql result = mangled(sql);
        assertEquals("VALUES (:HV1)", result.sql());
        assertEquals(sql, result.restore());
        HostVariableReference ref = result.hostVariables().get(0);
        assertEquals("WS-CUST-ID", ref.dataName());
        assertEquals("WS-CUST-IND", ref.indicatorName());
    }

    @Test
    void INDICATORつきの指示変数も単一トークンへまとめる() {
        String sql = "VALUES (:WS-CUST-ID INDICATOR :WS-CUST-IND)";
        MangledSql result = mangled(sql);
        assertEquals("VALUES (:HV1)", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("WS-CUST-IND", result.hostVariables().get(0).indicatorName());
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
    void OF修飾を名前に畳み込んで復元する() {
        String sql = "SELECT C1 FROM T1 WHERE K = :A OF B";
        MangledSql result = mangled(sql);
        assertEquals("SELECT C1 FROM T1 WHERE K = :HV1", result.sql());
        assertEquals(sql, result.restore());
        assertEquals("A OF B", result.hostVariables().get(0).dataName());
    }

    @Test
    void IN修飾とピリオド修飾も名前に畳み込む() {
        MangledSql in = mangled("WHERE K = :A IN B");
        assertEquals("WHERE K = :HV1", in.sql());
        assertEquals("A IN B", in.hostVariables().get(0).dataName());

        MangledSql dotted = mangled("WHERE K = :A.B.C");
        assertEquals("WHERE K = :HV1", dotted.sql());
        assertEquals("A.B.C", dotted.hostVariables().get(0).dataName());
        assertEquals("WHERE K = :A.B.C", dotted.restore());
    }

    @Test
    void IN述語はホスト変数の修飾と取り違えない() {
        String sql = "WHERE :WS-CD IN (SELECT CD FROM T)";
        MangledSql result = mangled(sql);
        assertEquals("WHERE :HV1 IN (SELECT CD FROM T)", result.sql());
        assertEquals(sql, result.restore());
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
    void マングリング済みSQLはDb2z文法で解析できる() {
        String sql = "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM"
                + " WHERE SHOHIN_CD = :HOST-商品コード AND SOKO_CD = :HOST-倉庫コード";
        MangledSql result = mangled(sql);
        SqlAnalysisResult analyzed = new SqlStatementAnalyzer().analyze(
                new SqlBlock(sql, SqlBlockKind.EXECUTABLE,
                        new SourcePosition(1, 12), new SourcePosition(1, 20)));
        assertEquals(SqlAnalysis.FULL, analyzed.analysis());
        assertEquals(sql, result.restore());
    }

    @Test
    void 名前が続かないコロンはそのまま残して理由を記録する() {
        MangledSql result = mangler.mangle("SELECT : FROM T");
        assertEquals("SELECT : FROM T", result.sql());
        assertEquals(1, result.diagnostics().size());
        assertFalse(result.diagnostics().get(0).isBlank());
    }

    @Test
    void ハイフンで終わる名前はそのまま残して理由を記録する() {
        MangledSql result = mangler.mangle("WHERE A = :WS-CUST-");
        assertEquals("WHERE A = :WS-CUST-", result.sql());
        assertEquals(1, result.diagnostics().size());
        assertEquals(List.of(), result.hostVariables());
    }

    @Test
    void 数字だけの名前はそのまま残して理由を記録する() {
        MangledSql result = mangler.mangle("WHERE A = :123");
        assertEquals("WHERE A = :123", result.sql());
        assertEquals(1, result.diagnostics().size());
    }

    @Test
    void 指示変数が不正ならホスト変数だけを取り出して理由を記録する() {
        MangledSql result = mangler.mangle("VALUES (:WS-A:9)");
        assertEquals("VALUES (:HV1:9)", result.sql());
        assertNull(result.hostVariables().get(0).indicatorName());
        assertEquals(1, result.diagnostics().size());
    }
}
