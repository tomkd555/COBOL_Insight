package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R006 添字への二進項目未使用の合成fixture検証。 */
class BinarySubscriptRuleTest {

    @TempDir
    Path tempDir;

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX006.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "       01  WS-TABLE-AREA.",
            /*  7 */ "           05  WS-ROW OCCURS 5 TIMES.",
            /*  8 */ "               10  WS-CELL             PIC 9(03).",
            /*  9 */ "       01  WS-SUB-DISP                 PIC 9(02).",
            /* 10 */ "       01  WS-SUB-COMP                 PIC S9(04) COMP.",
            /* 11 */ "       01  WS-BIN-GROUP                COMP.",
            /* 12 */ "           05  WS-SUB-INHERIT          PIC S9(04).",
            /* 13 */ "       01  WS-SUB-FULL                 PIC S9(04) USAGE COMPUTATIONAL.",
            /* 14 */ "       01  WS-IDX-TABLE.",
            /* 15 */ "           05  WS-IDX-ITEM OCCURS 3 TIMES PIC 9(02).",
            /* 16 */ "       01  WS-DISP-GROUP               USAGE DISPLAY.",
            /* 17 */ "           05  WS-SUB-DISP2            PIC 9(02).",
            /* 18 */ "       PROCEDURE DIVISION.",
            /* 19 */ "       0000-MAIN.",
            /* 20 */ "           MOVE 1 TO WS-CELL(WS-SUB-DISP)",
            /* 21 */ "           MOVE 2 TO WS-CELL(WS-SUB-COMP)",
            /* 22 */ "           MOVE 3 TO WS-CELL(1)",
            /* 23 */ "           MOVE 4 TO WS-CELL(WS-SUB-INHERIT)",
            /* 24 */ "           MOVE 5 TO WS-CELL(WS-SUB-FULL)",
            /* 25 */ "           MOVE 6 TO WS-CELL(WS-IDX-ITEM(1))",
            /* 26 */ "           MOVE 7 TO WS-CELL(WS-SUB-DISP2)",
            /* 27 */ "           GOBACK.",
            "");

    @Test
    void detectsNonBinarySubscriptOnly() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX006.cbl", SOURCE);

        List<Finding> findings = new BinarySubscriptRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertEquals(List.of(20, 25, 26),
                findings.stream().map(f -> f.location().line()).sorted().toList(),
                () -> "検出: " + findings);
        Finding finding = findings.stream()
                .filter(f -> f.location().line() == 20).findFirst().orElseThrow();
        assertEquals("R006", finding.ruleId());
        assertEquals(FindingLevel.NOTE, finding.level());
        assertTrue(finding.message().contains("WS-SUB-DISP"), finding.message());
        assertTrue(findings.stream().anyMatch(f -> f.location().line() == 26
                        && f.message().contains("WS-SUB-DISP2")),
                "集団項目配下の項目も添字判定の索引に載ること: " + findings);
    }

    @Test
    void inheritsUsageFromAncestorGroupItem() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX006B.cbl",
                SOURCE.replace("FIX006", "FIX006B"));

        List<Finding> findings = new BinarySubscriptRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-SUB-INHERIT")),
                "集団項目のUSAGE COMPを継承する添字項目は検出しないこと: " + findings);
    }

    @Test
    void acceptsComputationalFullSpelling() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX006C.cbl",
                SOURCE.replace("FIX006", "FIX006C"));

        List<Finding> findings = new BinarySubscriptRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertTrue(findings.stream().noneMatch(f -> f.message().contains("WS-SUB-FULL")),
                "USAGE COMPUTATIONAL完全綴りを二進とみなすこと: " + findings);
    }

    @Test
    void detectsNonBinarySubscriptOfOuterTableInNestedSubscript() {
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX006D.cbl",
                SOURCE.replace("FIX006", "FIX006D"));

        List<Finding> findings = new BinarySubscriptRule()
                .evaluate(Fixtures.context(List.of(model), Map.of(model.sourceFile(), SOURCE)));

        assertEquals(1, findings.stream()
                        .filter(f -> f.message().contains("WS-IDX-ITEM")).count(),
                "入れ子添字の外側表参照でも添字項目のUSAGEを判定すること: " + findings);
    }
}
