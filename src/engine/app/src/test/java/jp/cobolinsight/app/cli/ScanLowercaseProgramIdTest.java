package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.ScanOutcome;
import jp.cobolinsight.app.pipeline.Persist;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a program with a lowercase PROGRAM-ID resolves to the same node as the JCL that
 * references it in uppercase (consistency between the concrete program node's ID-generation rule
 * and the uppercasing on the call side).
 */
class ScanLowercaseProgramIdTest {

    @TempDir
    Path tempDir;

    @Test
    void lowercaseProgramIdDoesNotDuplicateProgramNode() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets.resolve("cobol"));
        Files.createDirectories(assets.resolve("jcl"));
        Files.writeString(assets.resolve("cobol").resolve("lowpgm1.cbl"), String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  lowpgm1.",
                "       PROCEDURE DIVISION.",
                "       MAIN-RTN.",
                "           GOBACK.",
                ""), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("jcl").resolve("JOBL1.jcl"), String.join("\n",
                "//JOBL1    JOB  (ACCT),'LOWERCASE',CLASS=A",
                "//STEP010  EXEC PGM=LOWPGM1",
                "//SYSOUT   DD   SYSOUT=*",
                ""), StandardCharsets.UTF_8);

        Path databaseFile = tempDir.resolve("scan.db");
        ScanOutcome result = Pipelines.scan(assets,
                databaseFile, List.of(), Map.of());

        long programNodes = result.callGraph().nodes().stream()
                .filter(n -> n.label().equalsIgnoreCase("LOWPGM1")).count();
        assertEquals(1, programNodes, "小文字PROGRAM-IDが二重ノード化しないこと: "
                + result.callGraph().toJson());
        assertTrue(result.callGraph().nodes().stream()
                        .filter(n -> n.label().equalsIgnoreCase("LOWPGM1"))
                        .allMatch(n -> n.attributes().isEmpty()),
                "実体ソースのあるプログラムを外部型付けしないこと");

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            // The only source-less node persisted at the graph layer should be the one STEP, and
            // the program node should map onto the existing row under the NODE.id=SOURCE.id convention.
            assertEquals("STEP",
                    dao.findNode(Persist.GRAPH_ID_BASE).orElseThrow().type());
            assertTrue(dao.findNode(Persist.GRAPH_ID_BASE + 1).isEmpty(),
                    "プログラムノードがグラフ層IDで二重に永続化されないこと");
        }
    }
}
