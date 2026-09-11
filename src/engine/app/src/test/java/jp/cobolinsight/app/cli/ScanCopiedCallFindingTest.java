package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.FindingRecord;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.analysis.linker.CallGraphLinker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A dispatcher kept in a COPY member: the CALL stands in the member, so the linker's record of how
 * it resolved carries the member's path rather than the program's. The row has to be filed against
 * the member instead of being dropped for want of a program of that name.
 */
class ScanCopiedCallFindingTest {

    private static final String MEMBER = """
                       MOVE 'SYK004' TO WS-PGM.
                       CALL WS-PGM.
            """;

    private static final String PROGRAM = """
                   IDENTIFICATION DIVISION.
                   PROGRAM-ID. DISPPGM.
                   DATA DIVISION.
                   WORKING-STORAGE SECTION.
                   01  WS-PGM                 PIC X(08).
                   PROCEDURE DIVISION.
                   MAIN-PARA.
                       COPY DISPATCH.
                       GOBACK.
            """;

    @Test
    void aDynamicCallInsideACopyMemberIsFiledAgainstTheMember(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("DISPATCH.cpy"), MEMBER, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("DISPPGM.cbl"), PROGRAM, StandardCharsets.UTF_8);
        Path databaseFile = dir.resolve("scan.db");

        Pipelines.scan(dir, databaseFile, List.of(dir), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            SourceRecord member = dao.findAllSources().stream()
                    .filter(row -> row.path().equals("DISPATCH.cpy")).findFirst().orElseThrow();
            List<String> ruleIds = dao.findFindingsBySource(member.id()).stream()
                    .map(FindingRecord::ruleId).toList();
            assertEquals(List.of(CallGraphLinker.DYNAMIC_CALL_RESOLVED_RULE_ID), ruleIds,
                    () -> "コピー句に立つ動的 CALL の記録がコピー句へ保存されること: " + ruleIds);
        }
    }
}
