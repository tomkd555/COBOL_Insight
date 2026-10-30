package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.persistence.PersistenceDao;
import jp.cobolinsight.app.persistence.PersistenceDatabase;
import jp.cobolinsight.app.persistence.model.ParagraphEdgeRecord;
import jp.cobolinsight.app.persistence.model.ParagraphRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the paragraph flow of schema version 6 end-to-end, starting from scan: the kind and
 * section of each PARAGRAPH row, and the PARAGRAPH_EDGE rows of PERFORMs and GO TOs written in a
 * phrase, SORT procedures, THRU ends, CALLs, EXEC CICS LINK/XCTL, ALTER and EXEC CICS HANDLE, with
 * the order and the program lines they are stored with. A THRU name that matches no paragraph
 * fails the parse, so its empty id is covered by the DAO round trip alone.
 */
class ScanParagraphFlowTest {

    @TempDir
    Path tempDir;

    private static final String SECTIONS = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM1.",                           // 2
            "       ENVIRONMENT DIVISION.",                            // 3
            "       INPUT-OUTPUT SECTION.",                            // 4
            "       FILE-CONTROL.",                                    // 5
            "           SELECT INFILE  ASSIGN TO INFILE",              // 6
            "               ORGANIZATION IS INDEXED",                  // 7
            "               ACCESS MODE IS DYNAMIC",                   // 8
            "               RECORD KEY IS IN-KEY.",                    // 9
            "           SELECT SORTWK  ASSIGN TO SORTWK.",             // 10
            "       DATA DIVISION.",                                   // 11
            "       FILE SECTION.",                                    // 12
            "       FD  INFILE.",                                      // 13
            "       01  IN-REC.",                                      // 14
            "           05  IN-KEY      PIC X(10).",                   // 15
            "           05  FILLER      PIC X(70).",                   // 16
            "       SD  SORTWK.",                                      // 17
            "       01  SORT-REC.",                                    // 18
            "           05  SORT-KEY    PIC X(10).",                   // 19
            "           05  FILLER      PIC X(70).",                   // 20
            "       WORKING-STORAGE SECTION.",                         // 21
            "       01  WS-FLAG         PIC X VALUE 'N'.",             // 22
            "       PROCEDURE DIVISION.",                              // 23
            "       0000-MAIN SECTION.",                               // 24
            "       0000-START.",                                      // 25
            "           PERFORM 1000-READ",                            // 26
            "           PERFORM 2000-A THRU 2000-C",                   // 27
            "           SORT SORTWK ON ASCENDING KEY SORT-KEY",        // 28
            "                INPUT PROCEDURE IS 3000-IN THRU 3000-IN-EXIT", // 29
            "                OUTPUT PROCEDURE IS 4000-OUT",            // 30
            "           GO TO 9000-END.",                              // 31
            "       1000-READ SECTION.",                               // 32
            "       1000-START.",                                      // 33
            "           READ INFILE",                                  // 34
            "               INVALID KEY GO TO 9000-END",               // 35
            "           END-READ",                                     // 36
            "           READ INFILE NEXT RECORD",                      // 37
            "               AT END PERFORM 2000-A",                    // 38
            "           END-READ.",                                    // 39
            "       2000-WORK SECTION.",                               // 40
            "       2000-A.",                                          // 41
            "           MOVE 'A' TO WS-FLAG.",                         // 42
            "       2000-B.",                                          // 43
            "           MOVE 'B' TO WS-FLAG.",                         // 44
            "       2000-C.",                                          // 45
            "           EXIT.",                                        // 46
            "       3000-IN SECTION.",                                 // 47
            "       3000-START.",                                      // 48
            "           MOVE SPACES TO SORT-REC",                      // 49
            "           RELEASE SORT-REC.",                            // 50
            "       3000-IN-EXIT SECTION.",                            // 51
            "       3000-EXIT.",                                       // 52
            "           EXIT.",                                        // 53
            "       4000-OUT SECTION.",                                // 54
            "       4000-START.",                                      // 55
            "           RETURN SORTWK",                                // 56
            "               AT END MOVE 'Y' TO WS-FLAG",               // 57
            "           END-RETURN.",                                  // 58
            "       9000-END SECTION.",                                // 59
            "       9000-START.",                                      // 60
            "           STOP RUN.",                                    // 61
            "");

    /**
     * One sentence with no period before GOBACK: the EXEC CICS after each CALL is a statement of
     * its own, although Che4z reads it into the USING list of the CALL, and the rows are the ones
     * a period after every statement gives.
     */
    private static final String CALLS = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM2.",                           // 2
            "       DATA DIVISION.",                                   // 3
            "       WORKING-STORAGE SECTION.",                         // 4
            "       01  WS-PGM          PIC X(8) VALUE 'SUBPGM2'.",    // 5
            "       01  WS-AREA         PIC X(10).",                   // 6
            "       PROCEDURE DIVISION.",                              // 7
            "       0000-MAIN.",                                       // 8
            "           CALL 'SUBPGM1' USING WS-AREA",                 // 9
            "           CALL WS-PGM USING WS-AREA",                    // 10
            "           EXEC CICS LINK PROGRAM('CICSPGM1')",           // 11
            "                COMMAREA(WS-AREA) LENGTH(10)",            // 12
            "           END-EXEC",                                     // 13
            "           EXEC CICS XCTL PROGRAM(WS-PGM)",               // 14
            "           END-EXEC",                                     // 15
            "           GOBACK.",                                      // 16
            "");

    private static final String CALL_PHRASES = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM6.",                           // 2
            "       DATA DIVISION.",                                   // 3
            "       WORKING-STORAGE SECTION.",                         // 4
            "       01  WS-AREA         PIC X(10).",                   // 5
            "       PROCEDURE DIVISION.",                              // 6
            "       0000-MAIN.",                                       // 7
            "           CALL 'SUBA' ON EXCEPTION PERFORM 9100-ERR END-CALL", // 8
            "           CALL 'SUBB' USING WS-AREA",                    // 9
            "               NOT ON EXCEPTION PERFORM 9200-OK",         // 10
            "           END-CALL",                                     // 11
            "           CALL 'SUBC' ON OVERFLOW PERFORM 9100-ERR END-CALL", // 12
            "           GOBACK.",                                      // 13
            "       9100-ERR.",                                        // 14
            "           EXIT.",                                        // 15
            "       9200-OK.",                                         // 16
            "           EXIT.",                                        // 17
            "");

    /** Every kind of row from one paragraph, two of them from one statement in either order. */
    private static final String ORDER = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM7.",                           // 2
            "       ENVIRONMENT DIVISION.",                            // 3
            "       INPUT-OUTPUT SECTION.",                            // 4
            "       FILE-CONTROL.",                                    // 5
            "           SELECT F1 ASSIGN TO F1",                       // 6
            "               ORGANIZATION IS INDEXED",                  // 7
            "               ACCESS MODE IS DYNAMIC",                   // 8
            "               RECORD KEY IS F1-KEY.",                    // 9
            "       DATA DIVISION.",                                   // 10
            "       FILE SECTION.",                                    // 11
            "       FD  F1.",                                          // 12
            "       01  F1-REC.",                                      // 13
            "           05  F1-KEY      PIC X(10).",                   // 14
            "       PROCEDURE DIVISION.",                              // 15
            "       0000-MAIN.",                                       // 16
            "           READ F1 NEXT RECORD",                          // 17
            "               AT END PERFORM CLOSE-P",                   // 18
            "               NOT AT END GO TO NEXT-P",                  // 19
            "           END-READ",                                     // 20
            "           READ F1 INVALID KEY GO TO X NOT INVALID KEY PERFORM Y", // 21
            "           END-READ",                                     // 22
            "           CALL 'SUBPGM1'.",                              // 23
            "       NEXT-P.",                                          // 24
            "           STOP RUN.",                                    // 25
            "       CLOSE-P.",                                         // 26
            "           EXIT.",                                        // 27
            "       X.",                                               // 28
            "           EXIT.",                                        // 29
            "       Y.",                                               // 30
            "           EXIT.",                                        // 31
            "");

    /**
     * P1 is defined in SECT-A and in SECT-B, used inside each, and qualified from MAIN-SECT. Che4z
     * refuses the unqualified name from a third section as ambiguous, so the first-definition
     * case is covered by ProcedureNamesTest, which both Persist and CfgBuilder resolve through.
     */
    private static final String TWO_SECTIONS = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM8.",                           // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       MAIN-SECT SECTION.",                               // 4
            "       MAIN-START.",                                      // 5
            "           PERFORM P1 OF SECT-B",                         // 6
            "           GO TO P1 IN SECT-B.",                          // 7
            "       SECT-A SECTION.",                                  // 8
            "       A-START.",                                         // 9
            "           PERFORM P1",                                   // 10
            "           STOP RUN.",                                    // 11
            "       P1.",                                              // 12
            "           EXIT.",                                        // 13
            "       SECT-B SECTION.",                                  // 14
            "       B-START.",                                         // 15
            "           PERFORM P1",                                   // 16
            "           STOP RUN.",                                    // 17
            "       P1.",                                              // 18
            "           EXIT.",                                        // 19
            "");

    private static final String ALTER_AND_HANDLE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM9.",                           // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       0000-MAIN.",                                       // 4
            "           EXEC CICS HANDLE CONDITION",                   // 5
            "                MAPFAIL(8100-MAPFAIL)",                   // 6
            "                ERROR(9000-ERROR)",                       // 7
            "           END-EXEC",                                     // 8
            "           ALTER 1000-SWITCH TO PROCEED TO 2000-SECOND.", // 9
            "       1000-SWITCH.",                                     // 10
            "           GO TO 2000-FIRST.",                            // 11
            "       2000-FIRST.",                                      // 12
            "           STOP RUN.",                                    // 13
            "       2000-SECOND.",                                     // 14
            "           STOP RUN.",                                    // 15
            "       8100-MAPFAIL.",                                    // 16
            "           STOP RUN.",                                    // 17
            "       9000-ERROR.",                                      // 18
            "           STOP RUN.",                                    // 19
            "");

    private static final String WHENEVER = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM3.",                           // 2
            "       DATA DIVISION.",                                   // 3
            "       WORKING-STORAGE SECTION.",                         // 4
            "           EXEC SQL INCLUDE SQLCA END-EXEC.",             // 5
            "       PROCEDURE DIVISION.",                              // 6
            "       0000-MAIN.",                                       // 7
            "           EXEC SQL WHENEVER SQLERROR GO TO 9900-SQL-ERROR END-EXEC", // 8
            "           EXEC SQL UPDATE MYTAB SET COL1 = 1 END-EXEC",  // 9
            "           STOP RUN.",                                    // 10
            "       9900-SQL-ERROR.",                                  // 11
            "           DISPLAY 'SQL ERROR'",                          // 12
            "           STOP RUN.",                                    // 13
            "");

    private static final String SHARED_NAMES = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM4.",                           // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       A-SECT SECTION.",                                  // 4
            "       A-START.",                                         // 5
            "           PERFORM B-SECT.",                              // 6
            "       COMMON-EXIT.",                                     // 7
            "           STOP RUN.",                                    // 8
            "       B-SECT SECTION.",                                  // 9
            "       B-START.",                                         // 10
            "           PERFORM B-WORK THRU COMMON-EXIT.",             // 11
            "       B-WORK.",                                          // 12
            "           CONTINUE.",                                    // 13
            "       COMMON-EXIT.",                                     // 14
            "           EXIT.",                                        // 15
            "");

    private static final String COPIED_TWICE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM5.",                           // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       FIRST-PARA.",                                      // 4
            "           COPY ERRHNDL.",                                // 5
            "       SECOND-PARA.",                                     // 6
            "           COPY ERRHNDL.",                                // 7
            "           STOP RUN.",                                    // 8
            "       LOG-ERROR.",                                       // 9
            "           EXIT.",                                        // 10
            "");

    private static final String ERRHNDL = String.join("\n",
            "           PERFORM LOG-ERROR.",                           // 1
            "           CALL 'ERRLOG'.",                               // 2
            "");

    /** A CICS LINK, a CALL and a GO TO DEPENDING ON written in phrases, and the statement form. */
    private static final String IN_PHRASES = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM11.",                          // 2
            "       ENVIRONMENT DIVISION.",                            // 3
            "       INPUT-OUTPUT SECTION.",                            // 4
            "       FILE-CONTROL.",                                    // 5
            "           SELECT F1 ASSIGN TO F1.",                      // 6
            "       DATA DIVISION.",                                   // 7
            "       FILE SECTION.",                                    // 8
            "       FD  F1.",                                          // 9
            "       01  F1-REC       PIC X(10).",                      // 10
            "       WORKING-STORAGE SECTION.",                         // 11
            "       01  WS-X         PIC 9.",                          // 12
            "       PROCEDURE DIVISION.",                              // 13
            "       0000-MAIN.",                                       // 14
            "           READ F1 AT END EXEC CICS LINK PROGRAM('CICSPGM1') END-EXEC", // 15
            "           END-READ",                                     // 16
            "           READ F1 AT END CALL 'SUBX' END-CALL END-READ", // 17
            "           READ F1 AT END GO TO A-PARA B-PARA DEPENDING ON WS-X", // 18
            "           END-READ",                                     // 19
            "           GO TO A-PARA DEPENDING ON WS-X.",              // 20
            "       A-PARA.",                                          // 21
            "           STOP RUN.",                                    // 22
            "       B-PARA.",                                          // 23
            "           STOP RUN.",                                    // 24
            "");

    /** A paragraph a COPY member defines, and a COPY member that copies another. */
    private static final String COPIED_PARAGRAPH = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM10.",                          // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       MAIN-PARA.",                                       // 4
            "           PERFORM WORK-PARA",                            // 5
            "           COPY EXITS.",                                  // 6
            "       MAIN-END.",                                        // 7
            "           STOP RUN.",                                    // 8
            "       WORK-PARA.",                                       // 9
            "           COPY OUTER.",                                  // 10
            "       LOG-ERROR.",                                       // 11
            "           EXIT.",                                        // 12
            "");

    /** INNER copied inside OUTER, then copied directly by the next statement. */
    private static final String NESTED_THEN_DIRECT = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM12.",                          // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       WORK-PARA.",                                       // 4
            "           COPY OUTER.",                                  // 5
            "           COPY INNER.",                                  // 6
            "       LOG-ERROR.",                                       // 7
            "           EXIT.",                                        // 8
            "");

    /** One copybook line holding an IF and the CALL in it, copied twice into one paragraph. */
    private static final String ONE_LINE_TWICE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM13.",                          // 2
            "       DATA DIVISION.",                                   // 3
            "       WORKING-STORAGE SECTION.",                         // 4
            "       01  WS-X         PIC 9.",                          // 5
            "       PROCEDURE DIVISION.",                              // 6
            "       MAIN-PARA.",                                       // 7
            "           COPY ONELINE.",                                // 8
            "           COPY ONELINE.",                                // 9
            "           STOP RUN.",                                    // 10
            "");

    /** Two COPY statements on one program line, and a copybook holding two on one line. */
    private static final String TWO_COPIES_ON_A_LINE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM14.",                          // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       MAIN-PARA.",                                       // 4
            "           COPY CALLA. COPY CALLB.",                      // 5
            "           COPY TWOCALLS.",                               // 6
            "           STOP RUN.",                                    // 7
            "");

    /** COPY statements written after other code on their lines. */
    private static final String COPIES_AFTER_CODE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM15.",                          // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       MAIN-PARA.",                                       // 4
            "           DISPLAY 'A' COPY CALLA.",                      // 5
            "           DISPLAY 'B' COPY CALLB. DISPLAY 'C'.",         // 6
            "           DISPLAY 'D' COPY TWOLINES.",                   // 7
            "           COPY MIDCALL.",                                // 8
            "           STOP RUN.",                                    // 9
            "");

    /** A copybook of two lines after code, with code and a second COPY after it. */
    private static final String CODE_AFTER_A_COPY = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  FLOWPGM16.",                          // 2
            "       PROCEDURE DIVISION.",                              // 3
            "       MAIN-PARA.",                                       // 4
            "           CALL 'MAIN1' COPY TWOLINES. CALL 'MAIN2'.",    // 5
            "           CALL 'MAIN3' COPY TWOLINES. CALL 'MAIN4' COPY CALLB.", // 6
            "           STOP RUN.",                                    // 7
            "");

    private static final String EXITS = String.join("\n",
            "           PERFORM LOG-ERROR.",                           // 1
            "       COMMON-EXIT.",                                     // 2
            "           GO TO MAIN-END.",                              // 3
            "");

    private static final String OUTER = String.join("\n",
            "           CALL 'OUTERPGM'.",                             // 1
            "           COPY INNER.",                                  // 2
            "           PERFORM LOG-ERROR.",                           // 3
            "");

    private static final String INNER = String.join("\n",
            "           CALL 'INNERPGM'.",                             // 1
            "");

    /** The rows of one scanned program. */
    private record Flow(List<ParagraphRecord> paragraphs, List<ParagraphEdgeRecord> edges) {

        String nameOf(long paragraphId) {
            return paragraphs.stream().filter(p -> p.id() == paragraphId).findFirst()
                    .orElseThrow().name();
        }

        long idOf(String name) {
            return paragraphs.stream().filter(p -> p.name().equals(name)).findFirst()
                    .orElseThrow().id();
        }

        /** Each edge as {@code FROM KIND TO [THRU x] @line}, in edge ID order. */
        List<String> describe() {
            return edges.stream().map(e -> nameOf(e.fromParagraph()) + " " + e.kind() + " "
                    + e.toName() + (e.thruName() == null ? "" : " THRU " + e.thruName())
                    + " @" + e.line()).toList();
        }
    }

    private Flow scan(String fileName, String source) throws IOException, SQLException {
        Path assets = tempDir.resolve(fileName + "-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve(fileName), source, StandardCharsets.UTF_8);
        Path databaseFile = tempDir.resolve(fileName + ".db");
        Pipelines.scan(assets, databaseFile, List.of(), Map.of());

        try (PersistenceDatabase database = PersistenceDatabase.open(databaseFile)) {
            PersistenceDao dao = new PersistenceDao(database.connection());
            List<ParagraphRecord> paragraphs = new ArrayList<>();
            try (Statement st = database.connection().createStatement();
                    ResultSet rs = st.executeQuery("SELECT id FROM PARAGRAPH ORDER BY id")) {
                while (rs.next()) {
                    paragraphs.add(dao.findParagraph(rs.getLong(1)).orElseThrow());
                }
            }
            long programSourceId = dao.findAllSources().stream()
                    .filter(record -> record.path().endsWith(fileName)).findFirst()
                    .orElseThrow().id();
            return new Flow(paragraphs, dao.findParagraphEdgesByProgram(programSourceId));
        }
    }

    @Test
    void paragraphsCarryTheirKindAndSection() throws Exception {
        Flow flow = scan("FLOWPGM1.cbl", SECTIONS);

        assertEquals(List.of(
                "0000-MAIN SECTION null", "0000-START PARAGRAPH 0000-MAIN",
                "1000-READ SECTION null", "1000-START PARAGRAPH 1000-READ",
                "2000-WORK SECTION null", "2000-A PARAGRAPH 2000-WORK",
                "2000-B PARAGRAPH 2000-WORK", "2000-C PARAGRAPH 2000-WORK"),
                flow.paragraphs().stream().limit(8)
                        .map(p -> p.name() + " " + p.kind() + " " + p.sectionName()).toList(),
                "a section is listed before its paragraphs");
    }

    @Test
    void performsAndGoTosAttachToTheParagraphThatWritesThem() throws Exception {
        Flow flow = scan("FLOWPGM1.cbl", SECTIONS);

        List<String> fromMainAndRead = flow.describe().stream()
                .filter(e -> e.startsWith("0000-") || e.startsWith("1000-")).toList();
        assertEquals(List.of(
                "0000-MAIN FALLTHROUGH 0000-START @null",
                "0000-START PERFORM 1000-READ @26",
                "0000-START PERFORM 2000-A THRU 2000-C @27",
                "0000-START PERFORM 3000-IN THRU 3000-IN-EXIT @28",
                "0000-START PERFORM 4000-OUT @28",
                "0000-START GOTO 9000-END @31",
                "1000-READ FALLTHROUGH 1000-START @null",
                "1000-START GOTO 9000-END @35",
                "1000-START PERFORM 2000-A @38",
                "1000-START FALLTHROUGH 2000-WORK @null"),
                fromMainAndRead, () -> "flow: " + flow.describe());

        for (ParagraphEdgeRecord edge : flow.edges()) {
            assertEquals(flow.idOf(edge.toName()), edge.toParagraph(), () -> "to: " + edge);
            Long thru = edge.thruName() == null ? null : flow.idOf(edge.thruName());
            assertEquals(thru, edge.thruParagraph(), () -> "thru: " + edge);
        }
    }

    @Test
    void callsAndCicsTransfersLeaveTheProgram() throws Exception {
        Flow flow = scan("FLOWPGM2.cbl", CALLS);

        assertEquals(List.of(
                "0000-MAIN CALL SUBPGM1 @9",
                "0000-MAIN CALL WS-PGM @10",
                "0000-MAIN CALL CICSPGM1 @11",
                "0000-MAIN XCTL WS-PGM @14"),
                flow.describe());
        for (ParagraphEdgeRecord edge : flow.edges()) {
            assertNull(edge.toParagraph(), () -> "CALL and XCTL have no target paragraph: " + edge);
        }
    }

    @Test
    void aNameUsedInTwoSectionsMeansTheParagraphOfTheSectionItIsUsedIn() throws Exception {
        Flow flow = scan("FLOWPGM4.cbl", SHARED_NAMES);

        long exitOfB = flow.paragraphs().stream()
                .filter(p -> p.name().equals("COMMON-EXIT") && "B-SECT".equals(p.sectionName()))
                .findFirst().orElseThrow().id();
        for (ParagraphEdgeRecord edge : flow.edges()) {
            if (edge.fromParagraph() == flow.idOf("B-START") && "PERFORM".equals(edge.kind())) {
                assertEquals(exitOfB, edge.thruParagraph(), () -> "thru: " + edge);
            }
            if (edge.fromParagraph() == flow.idOf("B-WORK")) {
                assertEquals("FALLTHROUGH", edge.kind());
                assertEquals(exitOfB, edge.toParagraph(), () -> "fall-through: " + edge);
            }
        }
        assertEquals(List.of("B-START PERFORM B-WORK THRU COMMON-EXIT @11",
                "B-START FALLTHROUGH B-WORK @null", "B-WORK FALLTHROUGH COMMON-EXIT @null"),
                flow.describe().stream().filter(e -> e.startsWith("B-START")
                        || e.startsWith("B-WORK")).toList(), () -> "flow: " + flow.describe());
    }

    @Test
    void aCopyMemberIncludedTwiceGivesEachParagraphItsOwnFlows() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM5.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("ERRHNDL.cpy"), ERRHNDL, StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM5.cbl", COPIED_TWICE);

        assertEquals(List.of(
                "FIRST-PARA PERFORM LOG-ERROR @5",
                "FIRST-PARA CALL ERRLOG @5",
                "FIRST-PARA FALLTHROUGH SECOND-PARA @null",
                "SECOND-PARA PERFORM LOG-ERROR @7",
                "SECOND-PARA CALL ERRLOG @7"),
                flow.describe(), "each row carries the line of the COPY statement in the program");
    }

    @Test
    void aParagraphACopyMemberDefinesStandsOnItsCopyStatement() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM10.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("EXITS.cpy"), EXITS, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("OUTER.cpy"), OUTER, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("INNER.cpy"), INNER, StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM10.cbl", COPIED_PARAGRAPH);

        assertEquals(List.of("MAIN-PARA 4-6", "COMMON-EXIT 6-6", "MAIN-END 7-8",
                        "WORK-PARA 9-10", "LOG-ERROR 11-12"),
                flow.paragraphs().stream()
                        .map(p -> p.name() + " " + p.startLine() + "-" + p.endLine()).toList());
        assertEquals(List.of(
                "MAIN-PARA PERFORM WORK-PARA @5",
                "MAIN-PARA PERFORM LOG-ERROR @6",
                "MAIN-PARA FALLTHROUGH COMMON-EXIT @null",
                "COMMON-EXIT GOTO MAIN-END @6",
                "WORK-PARA CALL OUTERPGM @10",
                "WORK-PARA CALL INNERPGM @10",
                "WORK-PARA PERFORM LOG-ERROR @10",
                "WORK-PARA FALLTHROUGH LOG-ERROR @null"),
                flow.describe(), "a nested COPY carries the line of the outermost COPY statement");
    }

    @Test
    void aNestedCopyStaysOnItsOuterCopyStatementWhenTheMemberIsCopiedAgainBelow()
            throws Exception {
        Path assets = tempDir.resolve("FLOWPGM12.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("OUTER.cpy"), OUTER, StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("INNER.cpy"), INNER, StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM12.cbl", NESTED_THEN_DIRECT);

        assertEquals(List.of(
                "WORK-PARA CALL OUTERPGM @5",
                "WORK-PARA CALL INNERPGM @5",
                "WORK-PARA PERFORM LOG-ERROR @5",
                "WORK-PARA CALL INNERPGM @6",
                "WORK-PARA FALLTHROUGH LOG-ERROR @null"),
                flow.describe());
    }

    @Test
    void twoCopyStatementsOnOneLineEachGiveTheirRowsAtThatLine() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM14.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("CALLA.cpy"), "           CALL 'PGMA'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("CALLB.cpy"), "           CALL 'PGMB'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("TWOCALLS.cpy"), "           COPY CALLB. COPY CALLA.\n",
                StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM14.cbl", TWO_COPIES_ON_A_LINE);

        assertEquals(List.of(
                "MAIN-PARA CALL PGMA @5",
                "MAIN-PARA CALL PGMB @5",
                "MAIN-PARA CALL PGMB @6",
                "MAIN-PARA CALL PGMA @6"),
                flow.describe());
    }

    @Test
    void aCopyAfterCodeOnItsLineGivesItsRowsAtThatLine() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM15.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("CALLA.cpy"), "           CALL 'PGMA'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("CALLB.cpy"), "           CALL 'PGMB'.\n",
                StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("TWOLINES.cpy"),
                "           CALL 'PGMT1'.\n           CALL 'PGMT2'.\n", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("MIDCALL.cpy"), "           DISPLAY 'M' COPY CALLA.\n",
                StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM15.cbl", COPIES_AFTER_CODE);

        assertEquals(List.of(
                "MAIN-PARA CALL PGMA @5",
                "MAIN-PARA CALL PGMB @6",
                "MAIN-PARA CALL PGMT1 @7",
                "MAIN-PARA CALL PGMT2 @7",
                "MAIN-PARA CALL PGMA @8"),
                flow.describe());
    }

    @Test
    void codeAfterACopybookOfTwoLinesFollowsItsLastStatement() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM16.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("TWOLINES.cpy"),
                "           CALL 'PGMT1'.\n           CALL 'PGMT2'.\n", StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("CALLB.cpy"), "           CALL 'PGMB'.\n",
                StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM16.cbl", CODE_AFTER_A_COPY);

        assertEquals(List.of(
                "MAIN-PARA CALL MAIN1 @5",
                "MAIN-PARA CALL PGMT1 @5",
                "MAIN-PARA CALL PGMT2 @5",
                "MAIN-PARA CALL MAIN2 @5",
                "MAIN-PARA CALL MAIN3 @6",
                "MAIN-PARA CALL PGMT1 @6",
                "MAIN-PARA CALL PGMT2 @6",
                "MAIN-PARA CALL MAIN4 @6",
                "MAIN-PARA CALL PGMB @6"),
                flow.describe());
    }

    @Test
    void aStatementOnTheLineOfTheOneHoldingItStaysInTheSameInclusion() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM13.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("ONELINE.cpy"),
                "           IF WS-X = 1 CALL 'ERRLOG' END-IF.\n", StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM13.cbl", ONE_LINE_TWICE);

        assertEquals(List.of("MAIN-PARA CALL ERRLOG @8", "MAIN-PARA CALL ERRLOG @9"),
                flow.describe());
    }

    @Test
    void aPerformInAPhraseOfACallFollowsTheCall() throws Exception {
        Flow flow = scan("FLOWPGM6.cbl", CALL_PHRASES);

        assertEquals(List.of(
                "0000-MAIN CALL SUBA @8",
                "0000-MAIN PERFORM 9100-ERR @8",
                "0000-MAIN CALL SUBB @9",
                "0000-MAIN PERFORM 9200-OK @10",
                "0000-MAIN CALL SUBC @12",
                "0000-MAIN PERFORM 9100-ERR @12"),
                flow.describe().stream().filter(e -> e.startsWith("0000-MAIN")).toList());
    }

    @Test
    void theRowsOfOneStatementFollowTheOrderOfItsWords() throws Exception {
        Flow flow = scan("FLOWPGM7.cbl", ORDER);

        assertEquals(List.of(
                "0000-MAIN PERFORM CLOSE-P @18",
                "0000-MAIN GOTO NEXT-P @19",
                "0000-MAIN GOTO X @21",
                "0000-MAIN PERFORM Y @21",
                "0000-MAIN CALL SUBPGM1 @23",
                "0000-MAIN FALLTHROUGH NEXT-P @null"),
                flow.describe().stream().filter(e -> e.startsWith("0000-MAIN")).toList());
    }

    /** {@code seq} numbers the rows of each paragraph 1..n, whatever their kind. */
    @Test
    void seqRunsFromOneWithinEachParagraph() throws Exception {
        Flow flow = scan("FLOWPGM7.cbl", ORDER);

        Map<Long, List<Integer>> seqByParagraph = new TreeMap<>();
        for (ParagraphEdgeRecord edge : flow.edges()) {
            seqByParagraph.computeIfAbsent(edge.fromParagraph(), key -> new ArrayList<>())
                    .add(edge.seq());
        }
        assertEquals(List.of(1, 2, 3, 4, 5, 6), seqByParagraph.get(flow.idOf("0000-MAIN")),
                "PERFORM, GOTO, CALL and FALLTHROUGH rows of one paragraph");
        seqByParagraph.forEach((paragraph, seqs) -> assertEquals(
                IntStream.rangeClosed(1, seqs.size()).boxed().toList(), seqs,
                () -> "paragraph " + flow.nameOf(paragraph)));
    }

    @Test
    void aNameInTwoSectionsResolvesToItsOwnSectionOrToTheNamedOne() throws Exception {
        Flow flow = scan("FLOWPGM8.cbl", TWO_SECTIONS);

        long p1OfA = flow.paragraphs().stream()
                .filter(p -> p.name().equals("P1") && "SECT-A".equals(p.sectionName()))
                .findFirst().orElseThrow().id();
        long p1OfB = flow.paragraphs().stream()
                .filter(p -> p.name().equals("P1") && "SECT-B".equals(p.sectionName()))
                .findFirst().orElseThrow().id();
        List<String> targets = flow.edges().stream()
                .filter(e -> "P1".equals(e.toName()))
                .map(e -> flow.nameOf(e.fromParagraph()) + " " + e.kind() + " @" + e.line() + " "
                        + (e.toParagraph() == p1OfA ? "SECT-A" : e.toParagraph() == p1OfB
                                ? "SECT-B" : String.valueOf(e.toParagraph())))
                .toList();
        assertEquals(List.of(
                "MAIN-START PERFORM @6 SECT-B",
                "MAIN-START GOTO @7 SECT-B",
                "A-START PERFORM @10 SECT-A",
                "B-START PERFORM @16 SECT-B"),
                targets);
    }

    /** PARAGRAPH_EDGE holds the GO TO statements alone: an ALTER and a HANDLE label write no row. */
    @Test
    void alterAndHandleLabelsWriteNoRow() throws Exception {
        Flow flow = scan("FLOWPGM9.cbl", ALTER_AND_HANDLE);

        assertEquals(List.of("0000-MAIN FALLTHROUGH 1000-SWITCH @null"),
                flow.describe().stream().filter(e -> e.startsWith("0000-MAIN")).toList());
    }

    /**
     * What a phrase writes gives the rows its statement form gives, and a one-target GO TO
     * DEPENDING ON falls through when no target matches.
     */
    @Test
    void whatAPhraseWritesIsARowAndGoToDependingOnFallsThrough() throws Exception {
        Flow flow = scan("FLOWPGM11.cbl", IN_PHRASES);

        assertEquals(List.of(
                "0000-MAIN CALL CICSPGM1 @15",
                "0000-MAIN CALL SUBX @17",
                "0000-MAIN GOTO A-PARA @18",
                "0000-MAIN GOTO B-PARA @18",
                "0000-MAIN GOTO A-PARA @20",
                "0000-MAIN FALLTHROUGH A-PARA @null"),
                flow.describe().stream().filter(e -> e.startsWith("0000-MAIN")).toList());
    }

    /**
     * A THRU end ahead of the target keeps its name as written and stores no THRU paragraph: the
     * range of such a PERFORM is its target alone. (A THRU name no paragraph carries fails the
     * parse of the whole program, so it reaches no row.)
     */
    @Test
    void aThruEndAheadOfTheTargetStoresNoThruParagraph() throws Exception {
        Flow flow = scan("FLOWPGM13.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM13.",                      // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       A-PARA.",                                      // 4
                "           DISPLAY 'A'.",                             // 5
                "       MAIN-PARA.",                                   // 6
                "           PERFORM C-PARA THRU A-PARA",               // 7
                "           PERFORM A-PARA THRU C-PARA",               // 8
                "           STOP RUN.",                                // 9
                "       C-PARA.",                                      // 10
                "           DISPLAY 'C'.",                             // 11
                ""));

        List<ParagraphEdgeRecord> performs = flow.edges().stream()
                .filter(e -> e.kind().equals("PERFORM")).toList();
        assertEquals(List.of("A-PARA", "C-PARA"),
                performs.stream().map(ParagraphEdgeRecord::thruName).toList());
        assertEquals(null, performs.get(0).thruParagraph(), "A-PARA stands ahead of C-PARA");
        assertEquals(flow.idOf("C-PARA"), performs.get(1).thruParagraph());
    }

    /**
     * The first statement that cannot complete decides whether a paragraph falls through: dead
     * code after STOP RUN changes nothing, and EXEC CICS RETURN and XCTL leave for good.
     */
    @Test
    void deadCodeAfterAnEndAndCicsEndingsWriteNoFallthroughRow() throws Exception {
        Flow flow = scan("FLOWPGM14.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM14.",                      // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       A-PARA.",                                      // 4
                "           STOP RUN.",                                // 5
                "           DISPLAY 'DEAD'.",                          // 6
                "       B-PARA.",                                      // 7
                "           EXEC CICS RETURN END-EXEC.",               // 8
                "       C-PARA.",                                      // 9
                "           EXEC CICS RETURN TRANSID('T001') END-EXEC.", // 10
                "       D-PARA.",                                      // 11
                "           EXEC CICS XCTL PROGRAM('NEXTPGM') END-EXEC.", // 12
                "       E-PARA.",                                      // 13
                "           EXEC CICS SYNCPOINT END-EXEC.",            // 14
                "       F-PARA.",                                      // 15
                "           GOBACK.",                                  // 16
                ""));

        assertEquals(List.of("E-PARA FALLTHROUGH F-PARA @null"), flow.describe().stream()
                .filter(e -> e.contains("FALLTHROUGH")).toList());
    }

    /**
     * A THRU end qualified by its section resolves in that section, and a THRU end naming a
     * section stores the section's own row. Both names are stored as written without their
     * qualifier, the form the target's name takes.
     */
    @Test
    void aQualifiedThruEndAndASectionThruEndStoreTheirOwnRows() throws Exception {
        Flow flow = scan("FLOWPGM16.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM16.",                      // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       MAIN-SECT SECTION.",                           // 4
                "       M-PARA.",                                      // 5
                "           PERFORM A1 OF S1 THRU B1 OF S2",           // 6
                "           PERFORM S1 THRU S2",                       // 7
                "           STOP RUN.",                                // 8
                "       S1 SECTION.",                                  // 9
                "       B1.",                                          // 10
                "           DISPLAY 'B1 OF S1'.",                      // 11
                "       A1.",                                          // 12
                "           DISPLAY 'A1'.",                            // 13
                "       S2 SECTION.",                                  // 14
                "       B1.",                                          // 15
                "           DISPLAY 'B1 OF S2'.",                      // 16
                ""));

        List<ParagraphEdgeRecord> performs = flow.edges().stream()
                .filter(e -> e.kind().equals("PERFORM")).toList();
        assertEquals(List.of("A1 THRU B1", "S1 THRU S2"), performs.stream()
                .map(e -> e.toName() + " THRU " + e.thruName()).toList());
        long b1OfS2 = flow.paragraphs().stream()
                .filter(p -> p.name().equals("B1") && "S2".equals(p.sectionName()))
                .findFirst().orElseThrow().id();
        assertEquals(b1OfS2, performs.get(0).thruParagraph());
        assertEquals(flow.idOf("S2"), performs.get(1).thruParagraph());
    }

    /** A RETURN or XCTL with RESP or NOHANDLE comes back on failure, so its paragraph falls through. */
    @Test
    void aCicsEndingThatComesBackOnFailureKeepsTheFallthroughRow() throws Exception {
        Flow flow = scan("FLOWPGM17.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM17.",                      // 2
                "       DATA DIVISION.",                               // 3
                "       WORKING-STORAGE SECTION.",                     // 4
                "       01  WS-R PIC S9(8) COMP.",                     // 5
                "       PROCEDURE DIVISION.",                          // 6
                "       A-PARA.",                                      // 7
                "           EXEC CICS RETURN RESP(WS-R) END-EXEC.",    // 8
                "       B-PARA.",                                      // 9
                "           EXEC CICS XCTL PROGRAM('X') NOHANDLE END-EXEC.", // 10
                "       C-PARA.",                                      // 11
                "           GOBACK.",                                  // 12
                ""));

        assertEquals(List.of("A-PARA FALLTHROUGH B-PARA @null", "B-PARA FALLTHROUGH C-PARA @null"),
                flow.describe().stream().filter(e -> e.contains("FALLTHROUGH")).toList());
    }

    /** An inline PERFORM with no UNTIL, VARYING or TIMES runs once: a GO TO ending it leaves. */
    @Test
    void anInlinePerformThatRunsOnceAndEndsInAGoToWritesNoFallthroughRow() throws Exception {
        Flow flow = scan("FLOWPGM18.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM18.",                      // 2
                "       PROCEDURE DIVISION.",                          // 3
                "       A-PARA.",                                      // 4
                "           PERFORM",                                  // 5
                "               DISPLAY 'A'",                          // 6
                "               GO TO C-PARA",                         // 7
                "           END-PERFORM.",                             // 8
                "       B-PARA.",                                      // 9
                "           DISPLAY 'B'.",                             // 10
                "       C-PARA.",                                      // 11
                "           GOBACK.",                                  // 12
                ""));

        assertEquals(List.of("A-PARA GOTO C-PARA @7", "B-PARA FALLTHROUGH C-PARA @null"),
                flow.describe());
    }

    /** Two inclusions of one copybook, a program statement on the first one's line: two rows. */
    @Test
    void twoInclusionsWithAStatementBetweenOnOneLineGiveTwoRows() throws Exception {
        Path assets = tempDir.resolve("FLOWPGM15.cbl-assets");
        Files.createDirectories(assets);
        Files.writeString(assets.resolve("CB.cpy"), "           PERFORM P2.\n",
                StandardCharsets.UTF_8);
        Flow flow = scan("FLOWPGM15.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM15.",                      // 2
                "       ENVIRONMENT DIVISION.",                        // 3
                "       DATA DIVISION.",                               // 4
                "       WORKING-STORAGE SECTION.",                     // 5
                "       01  WS-D PIC 9.",                              // 6
                "       PROCEDURE DIVISION.",                          // 7
                "       P1.",                                          // 8
                "           COPY CB. DISPLAY 'A'.",                    // 9
                "           COPY CB.",                                 // 10
                "           STOP RUN.",                                // 11
                "       P2.",                                          // 12
                "           DISPLAY 'P2'.",                            // 13
                ""));

        assertEquals(List.of("P1 PERFORM P2 @9", "P1 PERFORM P2 @10"),
                flow.describe().stream().filter(e -> e.startsWith("P1")).toList());
    }

    /** A paragraph that ends in an IF whose every branch leaves by GO TO cannot fall through. */
    @Test
    void anIfThatLeavesOnEveryBranchWritesNoFallthroughRow() throws Exception {
        Flow flow = scan("FLOWPGM12.cbl", String.join("\n",
                "       IDENTIFICATION DIVISION.",                     // 1
                "       PROGRAM-ID.  FLOWPGM12.",                      // 2
                "       DATA DIVISION.",                               // 3
                "       WORKING-STORAGE SECTION.",                     // 4
                "       01  X PIC X.",                                 // 5
                "       PROCEDURE DIVISION.",                          // 6
                "       0000-MAIN.",                                   // 7
                "           IF X = 'Y'",                               // 8
                "               GO TO A-PARA",                         // 9
                "           ELSE",                                     // 10
                "               GO TO B-PARA",                         // 11
                "           END-IF.",                                  // 12
                "       A-PARA.",                                      // 13
                "           STOP RUN.",                                // 14
                "       B-PARA.",                                      // 15
                "           STOP RUN.",                                // 16
                ""));

        assertEquals(List.of("0000-MAIN GOTO A-PARA @9", "0000-MAIN GOTO B-PARA @11"),
                flow.describe().stream().filter(e -> e.startsWith("0000-MAIN")).toList());
    }

    @Test
    void wheneverGoToIsNoParagraphEdge() throws Exception {
        Flow flow = scan("FLOWPGM3.cbl", WHENEVER);

        assertEquals(List.of(), flow.describe(),
                "the GO TO of an EXEC SQL WHENEVER yields no edge");
    }
}
