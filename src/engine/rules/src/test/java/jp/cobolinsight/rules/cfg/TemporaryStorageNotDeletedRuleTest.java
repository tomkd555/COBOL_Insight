package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R045 synthetic fixture verification for a TS queue no program deletes. */
class TemporaryStorageNotDeletedRuleTest {

    @TempDir
    Path tempDir;

    private static final String WRITER_ONLY = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID. FIX045.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  WS-REC PIC X(100).",
            /*  6 */ "       PROCEDURE DIVISION.",
            /*  7 */ "       0000-MAIN.",
            /*  8 */ "           EXEC CICS WRITEQ TS QUEUE('FIXTSQ1')",
            /*  9 */ "                FROM(WS-REC) LENGTH(100)",
            /* 10 */ "           END-EXEC",
            /* 11 */ "           GOBACK.",
            "");

    private static final String WRITER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX045B.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-REC PIC X(100).",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS WRITEQ TS QUEUE('FIXTSQ2')",
            "                FROM(WS-REC) LENGTH(100)",
            "           END-EXEC",
            "           GOBACK.",
            "");

    private static final String DELETER = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. FIX045C.",
            "       PROCEDURE DIVISION.",
            "       0000-MAIN.",
            "           EXEC CICS DELETEQ TS QUEUE('FIXTSQ2')",
            "           END-EXEC",
            "           GOBACK.",
            "");

    @Test
    void detectsQueueThatNoProgramDeletes() {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX045.cbl", WRITER_ONLY);
        List<Finding> findings = new TemporaryStorageNotDeletedRule().evaluate(
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), WRITER_ONLY)));
        assertEquals(1, findings.size(), () -> "削除されないキュー1件を検出すること: " + findings);
        assertEquals("R045", findings.get(0).ruleId());
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line(), "QUEUE オペランドの行で報告すること");
        assertTrue(findings.get(0).message().contains("FIXTSQ1"), findings.get(0).message());
    }

    @Test
    void ignoresQueueThatAnotherProgramDeletes() {
        CobolSemanticModel writer = CfgFixtures.parse(tempDir, "FIX045B.cbl", WRITER);
        CobolSemanticModel deleter = CfgFixtures.parse(tempDir, "FIX045C.cbl", DELETER);
        List<Finding> findings = new TemporaryStorageNotDeletedRule().evaluate(
                CfgFixtures.context(List.of(writer, deleter), Map.of(
                        writer.sourceFile(), WRITER, deleter.sourceFile(), DELETER)));
        assertEquals(List.of(), findings, "解析対象の別プログラムが DELETEQ TS していれば対象外");
    }
}
