package jp.cobolinsight.cli;

import jp.cobolinsight.persistence.PersistenceDao;
import jp.cobolinsight.persistence.PersistenceDatabase;
import jp.cobolinsight.persistence.model.CallEdgeRecord;
import jp.cobolinsight.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.persistence.model.SourceRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実行順の永続化の検証。JCLのステップ順が CALL_EDGE.seq として残ること、段落間の流れが
 * PARAGRAPH_EDGE として PERFORM・GO TO・流下の3種で残ること、呼出関係グラフのJSONが
 * seq を伴うことを、走査から一貫して確かめる。
 */
class ScanExecutionOrderTest {

    @TempDir
    Path tempDir;

    private static final String COBOL = String.join("\n",
            "       IDENTIFICATION DIVISION.",           // 1
            "       PROGRAM-ID.  ORDPGM1.",              // 2
            "       DATA DIVISION.",                     // 3
            "       WORKING-STORAGE SECTION.",           // 4
            "       01  WS-FLAG    PIC X VALUE 'Y'.",    // 5
            "       PROCEDURE DIVISION.",                // 6
            "       0000-MAIN.",                         // 7
            "           PERFORM 1000-INIT.",             // 8
            "           IF WS-FLAG = 'Y'",               // 9
            "               GO TO 9000-END",             // 10
            "           END-IF.",                        // 11
            "       1000-INIT.",                         // 12
            "           MOVE 'N' TO WS-FLAG.",           // 13
            "       9000-END.",                          // 14
            "           GOBACK.",                        // 15
            "");

    private static final String JCL = String.join("\n",
            "//ORDJOB1  JOB  (ACCT),'ORDER',CLASS=A",    // 1
            "//STEP010  EXEC PGM=ORDPGM1",               // 2
            "//SYSOUT   DD   SYSOUT=*",                  // 3
            "//STEP020  EXEC PGM=ORDPGM1",               // 4
            "//SYSOUT   DD   SYSOUT=*",                  // 5
            "");

    @Test
    void stepOrderAndParagraphFlowArePersisted() throws IOException {
        Path assets = tempDir.resolve("assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("ORDPGM1.cbl"), COBOL, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("ORDJOB1.jcl"), JCL, StandardCharsets.UTF_8);

        Path databaseFile = tempDir.resolve("scan.db");
        ScanRunner.Result result = ScanRunner.runWithGraph(new ScanRunner.Options(assets,
                databaseFile, List.of(), Map.of()));

        assertTrue(result.callGraph().toJson().contains("\"seq\":1"),
                "グラフJSONの辺が順序を伴うこと: " + result.callGraph().toJson());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            Map<String, Long> sourceIdByPath = dao.findAllSources().stream()
                    .collect(Collectors.toMap(SourceRecord::path, SourceRecord::id));
            long jobNode = sourceIdByPath.get("ORDJOB1.jcl");
            long programSourceId = sourceIdByPath.get("ORDPGM1.cbl");

            // ジョブ→ステップの辺は原本のステップ順に並び、EXEC文の行を持つ
            List<CallEdgeRecord> stepEdges = dao.findEdgesFrom(jobNode).stream()
                    .filter(e -> "EXECUTION".equals(e.kind()) && e.id() >= ScanRunner.GRAPH_ID_BASE)
                    .sorted(Comparator.comparingInt(CallEdgeRecord::seq)).toList();
            assertEquals(List.of(1, 2), stepEdges.stream().map(CallEdgeRecord::seq).toList());
            assertEquals(List.of(2, 4), stepEdges.stream().map(CallEdgeRecord::line).toList());
            assertEquals(List.of("STEP010", "STEP020"), stepEdges.stream()
                    .map(e -> dao.findNode(e.toNode()).orElseThrow().label()).toList());

            // 段落間の流れ: 0000-MAIN の PERFORM(8行目)・GO TO(10行目)・流下の順、
            // 1000-INIT からは流下だけ、最後の 9000-END は出辺を持たない
            List<ParagraphEdgeRecord> edges = dao.findParagraphEdgesByProgram(programSourceId);
            assertEquals(List.of("PERFORM", "GOTO", "FALLTHROUGH", "FALLTHROUGH"),
                    edges.stream().map(ParagraphEdgeRecord::kind).toList(),
                    () -> "段落間の流れ: " + edges);
            assertEquals(List.of("1000-INIT", "9000-END", "1000-INIT", "9000-END"),
                    edges.stream().map(ParagraphEdgeRecord::toName).toList());
            assertEquals(List.of(1, 2, 3, 1),
                    edges.stream().map(ParagraphEdgeRecord::seq).toList());
            assertEquals(8, edges.get(0).line());
            assertEquals(10, edges.get(1).line());
            assertNull(edges.get(2).line(), "流下は文に対応しないため行を持たない");

            // 飛び先は PARAGRAPH の行IDで引けること
            for (ParagraphEdgeRecord edge : edges) {
                assertEquals(edge.toName(),
                        dao.findParagraph(edge.toParagraph()).orElseThrow().name());
            }
        }
    }
}
