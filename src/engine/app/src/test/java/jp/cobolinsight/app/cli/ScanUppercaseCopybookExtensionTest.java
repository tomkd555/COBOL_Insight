package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** コピー句の拡張子が大文字(.CPY)でも COPY エッジが生成されることの検証。 */
class ScanUppercaseCopybookExtensionTest {

    @TempDir
    Path tempDir;

    @Test
    void copyEdgeIsCreatedForUppercaseCopybookExtension() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.createDirectories(assets.resolve("copybook"));
        Files.writeString(assets.resolve("copybook").resolve("TESTCPY.CPY"), String.join("\n",
                "       01  TC-REC.",
                "           05  TC-COUNT            PIC 9(03).",
                ""), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("cobol").resolve("UPRCPY1.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  UPRCPY1.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY TESTCPY.",
                "       PROCEDURE DIVISION.",
                "       MAIN-RTN.",
                "           MOVE 1 TO TC-COUNT",
                "           GOBACK.",
                ""), StandardCharsets.UTF_8);

        Path databaseFile = tempDir.resolve("scan.db");
        ScanRunner.Summary summary = ScanRunner.run(new ScanRunner.Options(assets, databaseFile,
                List.of(assets.resolve("copybook")), Map.of()));
        assertEquals(0, summary.exitCode());
        assertEquals(2, summary.analyzed().size());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            Map<String, Long> idByPath = dao.findAllSources().stream()
                    .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));
            long copybookId = idByPath.get("copybook/TESTCPY.CPY");
            long programId = idByPath.get("cobol/UPRCPY1.cbl");
            boolean hasCopyEdge = dao.findEdgesFrom(copybookId).stream()
                    .anyMatch(e -> "COPY".equals(e.kind()) && e.toNode() == programId);
            assertTrue(hasCopyEdge, "大文字拡張子 .CPY のコピー句にも COPY エッジが生成されること");
        }
    }
}
