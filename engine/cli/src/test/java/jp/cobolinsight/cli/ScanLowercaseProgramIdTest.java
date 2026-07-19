package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
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
 * 小文字PROGRAM-IDのプログラムが、大文字で参照するJCLと同一ノードへ解決されることの検証
 * (実体プログラムノードのID生成規則と呼出先側の大文字化の整合)。
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
        ScanRunner.Result result = ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                databaseFile, List.of(), Map.of()));

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
            // グラフ層に永続化されるソース非対応ノードはステップ1件のみで、プログラムノードは
            // NODE.id=SOURCE.id 規約の既存行へ写像されること
            assertEquals("STEP",
                    dao.findNode(ScanRunner.GRAPH_ID_BASE).orElseThrow().type());
            assertTrue(dao.findNode(ScanRunner.GRAPH_ID_BASE + 1).isEmpty(),
                    "プログラムノードがグラフ層IDで二重に永続化されないこと");
        }
    }
}
