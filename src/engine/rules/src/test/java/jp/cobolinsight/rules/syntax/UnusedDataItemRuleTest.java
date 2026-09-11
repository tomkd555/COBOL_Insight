package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic fixture verification for R002 unused data items. */
class UnusedDataItemRuleTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX002.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       INPUT-OUTPUT SECTION.",
            /*  5 */ "       FILE-CONTROL.",
            /*  6 */ "           SELECT OPT-FILE ASSIGN TO OPTF",
            /*  7 */ "               FILE STATUS IS WS-ENV-ONLY-ITEM.",
            /*  8 */ "       DATA DIVISION.",
            /*  9 */ "       FILE SECTION.",
            /* 10 */ "       FD  OPT-FILE.",
            /* 11 */ "       01  OPT-REC.",
            /* 12 */ "           05  OPT-UNUSED-FIELD        PIC X(10).",
            /* 13 */ "       WORKING-STORAGE SECTION.",
            /* 14 */ "       01  WS-GROUP.",
            /* 15 */ "           05  WS-USED-CHILD           PIC 9(03).",
            /* 16 */ "           05  WS-UNUSED-CHILD         PIC 9(03).",
            /* 17 */ "       01  WS-FLAG                     PIC X(01).",
            /* 18 */ "           88  WS-FLAG-ON              VALUE '1'.",
            /* 19 */ "       01  WS-UNUSED-ALONE             PIC X(05).",
            /* 20 */ "       01  WS-COND-FLAG                PIC X(01).",
            /* 21 */ "           88  WS-COND-ON              VALUE '1'.",
            /* 22 */ "       01  WS-ENV-ONLY-ITEM            PIC X(02).",
            /* 23 */ "       LINKAGE SECTION.",
            /* 24 */ "       01  LK-PARM                     PIC X(05).",
            /* 25 */ "       PROCEDURE DIVISION USING LK-PARM.",
            /* 26 */ "       0000-MAIN.",
            /* 27 */ "           MOVE 1 TO WS-USED-CHILD",
            /* 28 */ "           IF WS-FLAG-ON",
            /* 29 */ "               CONTINUE",
            /* 30 */ "           END-IF",
            /* 31 */ "           GOBACK.",
            "");

    @Test
    void detectsUnusedWorkingStorageItemsAndSkipsLinkage() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX002.cbl", SOURCE);
        AnalysisContext context = Fixtures.context(List.of(model),
                Map.of(model.sourceFile(), SOURCE));

        List<Finding> findings = new UnusedDataItemRule().evaluate(context);

        assertEquals(2, findings.size(), () -> "検出: " + findings);
        Finding child = findings.stream()
                .filter(f -> f.message().contains("WS-UNUSED-CHILD")).findFirst().orElseThrow();
        assertEquals(16, child.location().line());
        assertEquals(FindingLevel.NOTE, child.level());
        assertEquals("R002", child.ruleId());
        Finding alone = findings.stream()
                .filter(f -> f.message().contains("WS-UNUSED-ALONE")).findFirst().orElseThrow();
        assertEquals(19, alone.location().line());
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("LK-PARM")),
                "LINKAGE SECTIONの項目は対象外であること");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-FLAG")),
                "88レベル条件名で参照される項目は使用済みであること");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-GROUP ")),
                "子が参照される集団項目は使用済みであること");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-COND-FLAG")),
                "88レベル条件名を宣言する項目は対象外であること");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-ENV-ONLY-ITEM")),
                "ENVIRONMENT DIVISION(FILE STATUS句)でのみ参照される項目は検出しないこと");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("OPT-REC")
                        || f.message().contains("OPT-UNUSED-FIELD")),
                "FILE SECTION(FD配下)の未参照レコード項目は対象外であること");
    }

    private static final String TABLE_COPYBOOK = String.join("\n",
            "       01  CPY-AREA.",
            "           05  CPY-USED                PIC X(05).",
            "           05  CPY-UNUSED              PIC X(05).",
            "       01  CPY-ALONE                   PIC X(05).",
            "");

    /** A SEARCH ALL table initialised through a REDEFINES, next to a copybook used in part. */
    private static final String REDEFINES_AND_COPY = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX002C.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "           COPY TBLCPY.",
            /*  6 */ "       01  WS-CODE-INIT.",
            /*  7 */ "           05  FILLER  PIC X(10) VALUE '10001TOKYO'.",
            /*  8 */ "           05  FILLER  PIC X(10) VALUE '10002OSAKA'.",
            /*  9 */ "       01  WS-CODE-TBL REDEFINES WS-CODE-INIT.",
            /* 10 */ "           05  WS-CODE-ENT OCCURS 2 TIMES",
            /* 11 */ "                   ASCENDING KEY IS WS-CODE INDEXED BY WS-CODE-IX.",
            /* 12 */ "               10  WS-CODE             PIC X(05).",
            /* 13 */ "               10  WS-CODE-NAME        PIC X(05).",
            /* 14 */ "       01  WS-KEY                      PIC X(05).",
            /* 15 */ "       01  WS-UNUSED                   PIC X(05).",
            /* 16 */ "       PROCEDURE DIVISION.",
            /* 17 */ "       0000-MAIN.",
            /* 18 */ "           MOVE CPY-USED TO WS-KEY",
            /* 19 */ "           SEARCH ALL WS-CODE-ENT",
            /* 20 */ "               WHEN WS-CODE (WS-CODE-IX) = WS-KEY",
            /* 21 */ "                   DISPLAY WS-CODE-NAME (WS-CODE-IX)",
            /* 22 */ "           END-SEARCH",
            /* 23 */ "           GOBACK.",
            "");

    @Test
    void skipsCopybookItemsAndTablesUsedThroughARedefines() throws java.io.IOException {
        Path copybookDir = tempDir.resolve("cpy");
        java.nio.file.Files.createDirectories(copybookDir);
        java.nio.file.Files.writeString(copybookDir.resolve("TBLCPY.cpy"), TABLE_COPYBOOK);
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX002C.cbl", REDEFINES_AND_COPY,
                copybookDir);
        AnalysisContext context = Fixtures.context(List.of(model), Map.of(
                model.sourceFile(), REDEFINES_AND_COPY,
                copybookDir.resolve("TBLCPY.cpy").toString(), TABLE_COPYBOOK));

        List<Finding> findings = new UnusedDataItemRule().evaluate(context);

        assertEquals(List.of(15), findings.stream().map(f -> f.location().line()).toList(),
                () -> "only WS-UNUSED: the copybook's fields and the REDEFINEd init group are "
                        + "not reported: " + findings);
    }

    private static final String RECORD_LAYOUT = String.join("\n",
            "           05  CPY-A                   PIC X(05).",
            "           05  CPY-B                   PIC X(05).",
            "");

    /** The common idiom of a record whose layout lives in a copybook below the program's own 01. */
    private static final String COPY_UNDER_AN_ITEM = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX002D.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-REC.",
            /*  6 */ "           COPY RECLAYOUT.",
            /*  7 */ "       PROCEDURE DIVISION.",
            /*  8 */ "       0000-MAIN.",
            /*  9 */ "           DISPLAY CPY-A",
            /* 10 */ "           GOBACK.",
            "");

    @Test
    void skipsCopybookFieldsPulledInUnderAnItemOfTheProgram() throws java.io.IOException {
        Path copybookDir = tempDir.resolve("cpy");
        java.nio.file.Files.createDirectories(copybookDir);
        java.nio.file.Files.writeString(copybookDir.resolve("RECLAYOUT.cpy"), RECORD_LAYOUT);
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX002D.cbl", COPY_UNDER_AN_ITEM,
                copybookDir);
        AnalysisContext context = Fixtures.context(List.of(model), Map.of(
                model.sourceFile(), COPY_UNDER_AN_ITEM,
                copybookDir.resolve("RECLAYOUT.cpy").toString(), RECORD_LAYOUT));

        assertEquals(List.of(), new UnusedDataItemRule().evaluate(context),
                "CPY-B belongs to the copybook, not to this program");
    }

    @Test
    void returnsNothingWithoutSourceTextIndex() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX002B.cbl",
                SOURCE.replace("FIX002", "FIX002B"));
        AnalysisContext context = jp.cobolinsight.core.spi.AnalysisContext.of(
                List.of(model), List.of(), List.of(), List.of(),
                java.util.Optional.empty(), Map.of());

        assertEquals(List.of(), new UnusedDataItemRule().evaluate(context),
                "ソーステキスト索引が無い場合は検出しない(誤検出よりも未検出を選ぶ)こと");
    }
}
