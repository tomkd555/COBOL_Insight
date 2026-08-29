package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.rules.FixApplyChecks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R018 の FixProducer 検証。SQLCODE を検査しないデータ変更 DML の EXEC SQL 直後(END-EXEC 行の
 * 次行)へ、SQLCODE を判定する IF 文を挿入する TextEdit を返すことを確認する。終止ピリオドは
 * END-EXEC が文を閉じている場合にのみ付き、囲む文の途中では END-IF だけで閉じる。
 */
class SqlCodeUncheckedFixTest {

    @TempDir
    Path tempDir;

    private static final String CHECK =
            "           IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF.\n";

    private static Finding finding(AnalysisContext context, String file, int line) {
        return new SqlCodeUncheckedRule().evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == line)
                .findFirst()
                .orElseThrow(() -> new AssertionError(file + ":" + line + " の R018 検出が前提"));
    }

    @Test
    void insertsSqlCodeCheckAfterUpdateInSyk006() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK006.cbl");
        Finding finding = finding(context, file, 119);

        FixSuggestion suggestion = new SqlCodeUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow(() -> new AssertionError("R018 の修正案が返ること"));
        assertEquals(1, suggestion.edits().size());
        TextEdit edit = suggestion.edits().get(0);
        // END-EXEC は119行。その直後(120行先頭)へ空範囲挿入する。
        assertEquals(120, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals(120, edit.range().end().line());
        assertEquals(1, edit.range().end().column());
        assertEquals(CHECK, edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R018(SYK006) 修正後ソースが再パースできること");
    }

    @Test
    void insertsSqlCodeCheckAfterUpdateInSyk007() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK007.cbl");
        Finding finding = finding(context, file, 89);

        FixSuggestion suggestion = new SqlCodeUncheckedRule().fix().orElseThrow()
                .produce(finding, context).orElseThrow();
        TextEdit edit = suggestion.edits().get(0);
        assertEquals(90, edit.range().start().line());
        assertEquals(1, edit.range().start().column());
        assertEquals(CHECK, edit.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R018(SYK007) 修正後ソースが再パースできること");
    }

    @Test
    void insertsPeriodlessCheckForBlockDml() {
        // IF ブロックの途中にある INSERT(END-EXEC に終止ピリオド無し)の直後へは、ピリオドを
        // 付けない IF … END-IF を挿入する。外側の IF は END-IF まで途切れない。
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX018M.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "           EXEC SQL INCLUDE SQLCA END-EXEC.",
                "       01  WS-FLAG PIC X.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           IF WS-FLAG = 'Y'",
                "               EXEC SQL",
                "                   INSERT INTO T1 VALUES (1)",
                "               END-EXEC",
                "               DISPLAY 'DONE'",
                "           END-IF.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX018M.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        SqlCodeUncheckedRule rule = new SqlCodeUncheckedRule();
        Finding finding = rule.evaluate(context).stream().findFirst()
                .orElseThrow(() -> new AssertionError("ブロック内 INSERT の R018 検出が前提"));
        FixSuggestion suggestion = rule.fix().orElseThrow().produce(finding, context)
                .orElseThrow(() -> new AssertionError("ブロック途中の INSERT にも修正案が返ること"));
        assertEquals("           IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF\n",
                suggestion.edits().get(0).replacement());

        assertTrue(FixApplyChecks.applyAndReparse(model.sourceFile(), suggestion.edits(), List.of())
                        .success(),
                "R018(ブロック途中) 修正後ソースが再パースできること");
    }
}
