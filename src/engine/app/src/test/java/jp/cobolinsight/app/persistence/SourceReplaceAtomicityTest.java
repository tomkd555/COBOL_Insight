package jp.cobolinsight.app.persistence;

import jp.cobolinsight.app.persistence.model.ProgramRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceReplaceAtomicityTest {

    private PersistenceDatabase db;
    private PersistenceDao dao;

    @BeforeEach
    void openDatabase(@TempDir Path dir) {
        db = PersistenceDatabase.open(dir.resolve("insight.db"));
        dao = new PersistenceDao(db.connection());
    }

    @AfterEach
    void closeDatabase() {
        db.close();
    }

    @Test
    void rollsBackFullReplaceWhenLaterStepFails() {
        dao.insertSource(new SourceRecord(1L, "/assets", "PROGA.cbl", "IBM930", "hash-v1", 100L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));

        assertThrows(PersistenceException.class, () -> dao.inTransaction(() -> {
            dao.deleteSourceCascade(1L);
            dao.insertSource(new SourceRecord(1L, "/assets", "PROGA.cbl", "IBM930", "hash-v2", 120L));
            // source_id=99 は存在しないため外部キー制約違反で失敗する
            dao.insertProgram(new ProgramRecord(2L, 99L, "PROGA"));
        }));

        SourceRecord source = dao.findSource(1L).orElseThrow();
        assertEquals("hash-v1", source.contentHash(), "失敗した差し替えはロールバックされ旧内容が残る");
        assertTrue(dao.findProgram(1L).isPresent(), "差し替え前のPROGRAM行もロールバックで復元される");
    }

    @Test
    void commitsFullReplaceWhenAllStepsSucceed() {
        dao.insertSource(new SourceRecord(1L, "/assets", "PROGA.cbl", "IBM930", "hash-v1", 100L));
        dao.insertProgram(new ProgramRecord(1L, 1L, "PROGA"));

        dao.inTransaction(() -> {
            dao.deleteSourceCascade(1L);
            dao.insertSource(new SourceRecord(1L, "/assets", "PROGA.cbl", "IBM930", "hash-v2", 120L));
            dao.insertProgram(new ProgramRecord(2L, 1L, "PROGA"));
        });

        SourceRecord source = dao.findSource(1L).orElseThrow();
        assertEquals("hash-v2", source.contentHash());
        assertTrue(dao.findProgram(1L).isEmpty(), "旧PROGRAM行はカスケード削除で消える");
        assertTrue(dao.findProgram(2L).isPresent());
    }
}
