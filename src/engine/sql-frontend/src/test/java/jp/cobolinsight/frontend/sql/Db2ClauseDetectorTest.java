package jp.cobolinsight.frontend.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Db2ClauseDetectorTest {

    private final Db2ClauseDetector detector = new Db2ClauseDetector();

    @Test
    void optimizeFor句を検出する() {
        String sql = "SELECT A FROM T ORDER BY A OPTIMIZE FOR 10 ROWS";
        List<Db2ClauseFinding> findings = detector.detect(sql);
        assertEquals(1, findings.size());
        assertEquals(Db2Clause.OPTIMIZE_FOR, findings.get(0).clause());
        assertEquals("OPTIMIZE FOR 10 ROWS", findings.get(0).matchedText());
        assertEquals(sql.indexOf("OPTIMIZE"), findings.get(0).offset());
    }

    @Test
    void 単数形rowのoptimizeFor句も検出する() {
        List<Db2ClauseFinding> findings = detector.detect(
                "SELECT A FROM T OPTIMIZE FOR 1 ROW");
        assertEquals(1, findings.size());
        assertEquals(Db2Clause.OPTIMIZE_FOR, findings.get(0).clause());
    }

    @Test
    void withUr句を検出する() {
        String sql = "SELECT A FROM T WHERE B = 1 WITH UR";
        List<Db2ClauseFinding> findings = detector.detect(sql);
        assertEquals(1, findings.size());
        assertEquals(Db2Clause.WITH_UR, findings.get(0).clause());
        assertEquals("WITH UR", findings.get(0).matchedText());
        assertEquals(sql.indexOf("WITH UR"), findings.get(0).offset());
    }

    @Test
    void 両句を同時に検出しオフセット順に返す() {
        String sql = "SELECT A FROM T OPTIMIZE FOR 5 ROWS WITH UR";
        List<Db2ClauseFinding> findings = detector.detect(sql);
        assertEquals(2, findings.size());
        assertEquals(Db2Clause.OPTIMIZE_FOR, findings.get(0).clause());
        assertEquals(Db2Clause.WITH_UR, findings.get(1).clause());
        assertTrue(findings.get(0).offset() < findings.get(1).offset());
    }

    @Test
    void 文字列リテラル内の句は検出しない() {
        assertEquals(List.of(), detector.detect("SELECT 'WITH UR' FROM T"));
    }

    @Test
    void 対象句がなければ空を返す() {
        assertEquals(List.of(), detector.detect(
                "SELECT ZAIKO_SU FROM SYKDB.ZAIKOM WHERE SHOHIN_CD = :HV1"));
    }
}
