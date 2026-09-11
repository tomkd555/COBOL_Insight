package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.SourceRecord;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.pipeline.Pipelines;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOURCE.id stays below {@code GRAPH_ID_BASE / ID_STRIDE}. Above it a child row's id reaches into
 * the graph layer, which every scan wipes and rebuilds, so the findings and call edges of that
 * source would go with it. The scan says so and writes nothing rather than lose the rows.
 */
class ScanSourceIdCeilingTest {

    /** The bound the ids of a source's child rows stay under; {@code Persist.ID_STRIDE} is private. */
    private static final long ID_STRIDE = 1_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void aScanIsRefusedOnceTheIdsWouldReachTheGraphLayer() throws IOException {
        Path assets = Files.createDirectories(tempDir.resolve("assets"));
        Files.writeString(assets.resolve("MINI.cpy"),
                "       01  MINI-ITEM                  PIC X(10).\n", StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve("ceiling.db");
        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            new PersistenceDao(database.connection()).insertSource(
                    new SourceRecord(Persist.GRAPH_ID_BASE / ID_STRIDE, "/another-folder",
                            "OLD.cbl", "UTF-8", "hash-old", 1L));
        }

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> Pipelines.scan(assets, databaseFile, List.of(), Map.of()));

        assertTrue(thrown.getMessage().startsWith("プロジェクトファイルに登録できる資産数の上限"),
                thrown.getMessage());
    }
}
