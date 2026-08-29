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
 * R021 の FixProducer 検証。END-EXEC の直前へ RESP オペランド行を、直後へ応答コードの判定文を
 * 挿入する2件の TextEdit を返すことを確認する。受け変数は WORKING-STORAGE の PIC S9(08) COMP
 * 相当で名前に RESP を含む基本項目に限り、該当が無ければ修正案を出さない。
 */
class CicsResponseUncheckedFixTest {

    @TempDir
    Path tempDir;

    private static Finding finding(AnalysisContext context, String file, int line) {
        return new CicsResponseUncheckedRule().evaluate(context).stream()
                .filter(f -> f.location().file().equals(file) && f.location().line() == line)
                .findFirst()
                .orElseThrow(() -> new AssertionError(file + ":" + line + " の R021 検出が前提"));
    }

    @Test
    void insertsRespOperandAndJudgementAroundEndExecInSyk008() {
        AnalysisContext context = CfgFixtures.samples();
        String file = CfgFixtures.samplesFile("SYK008.cbl");
        Finding finding = finding(context, file, 38);

        FixSuggestion suggestion = new CicsResponseUncheckedRule().fixProducer().orElseThrow()
                .produce(finding, context)
                .orElseThrow(() -> new AssertionError("R021 の修正案が返ること"));
        assertEquals(2, suggestion.edits().size());

        // END-EXEC は38行。オペランド行はその直前(38行先頭)へ入る。
        TextEdit operand = suggestion.edits().get(0);
        assertEquals(38, operand.range().start().line());
        assertEquals(1, operand.range().start().column());
        assertEquals(38, operand.range().end().line());
        assertEquals("           RESP(WS-RESPコード)\n", operand.replacement());

        // 判定文は END-EXEC の直後(39行先頭)へ入る。END-EXEC は文を閉じないため終止ピリオドを
        // 付けず、明示的な END-IF だけで閉じる。
        TextEdit judgement = suggestion.edits().get(1);
        assertEquals(39, judgement.range().start().line());
        assertEquals(1, judgement.range().start().column());
        assertEquals("           IF WS-RESPコード NOT = 0 DISPLAY\n"
                        + "           'SYK008 RECEIVE MAPエラー RESP=' WS-RESPコード END-IF\n",
                judgement.replacement());

        assertTrue(FixApplyChecks.applyAndReparse(file, suggestion.edits(),
                        List.of(CfgFixtures.SAMPLES.resolve("copybook"))).success(),
                "R021 修正後ソースが再パースできること");
    }

    @Test
    void skipsFixWhenNoRespReceiverIsDeclared() {
        // RESP を受けられる PIC S9(08) COMP 相当の項目が無ければ、検出は続けるが修正案を出さない。
        // WORKING-STORAGE への宣言追加は行わない。
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX021C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-MAP.",
                "           05  WS-F PIC X(08).",
                "       01  WS-RESP-TEXT PIC X(08).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC CICS",
                "               RECEIVE MAP('MAP1')",
                "                   MAPSET('SET1')",
                "                   INTO(WS-MAP)",
                "           END-EXEC.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021C.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        CicsResponseUncheckedRule rule = new CicsResponseUncheckedRule();
        Finding finding = rule.evaluate(context).get(0);

        assertTrue(rule.fixProducer().orElseThrow().produce(finding, context).isEmpty(),
                "受け変数が無ければ修正案を出さないこと");
    }

    @Test
    void closesJudgementWithPeriodWhenEndExecEndsSentence() {
        // END-EXEC が終止ピリオドで文を閉じている場合は、判定文も終止ピリオドで閉じる。
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. FIX021D.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-MAP.",
                "           05  WS-F PIC X(08).",
                "       01  WS-応答コード.",
                "           05  WS-RESPコード  PIC S9(08) COMP.",
                "           05  WS-RESP2コード PIC S9(08) COMP.",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC CICS",
                "               RECEIVE MAP('MAP1')",
                "                   MAPSET('SET1')",
                "                   INTO(WS-MAP)",
                "           END-EXEC.",
                "           GOBACK.",
                "");
        CobolSemanticModel model = CfgFixtures.parse(tempDir, "FIX021D.cbl", text);
        AnalysisContext context =
                CfgFixtures.context(List.of(model), Map.of(model.sourceFile(), text));
        CicsResponseUncheckedRule rule = new CicsResponseUncheckedRule();
        Finding finding = rule.evaluate(context).get(0);

        FixSuggestion suggestion = rule.fixProducer().orElseThrow().produce(finding, context)
                .orElseThrow();
        // RESP2 を含む名前は RESP の受け変数として選ばない。
        assertEquals("           RESP(WS-RESPコード)\n",
                suggestion.edits().get(0).replacement());
        assertTrue(suggestion.edits().get(1).replacement().contains("END-IF.\n"),
                suggestion.edits().get(1).replacement());

        assertTrue(FixApplyChecks.applyAndReparse(model.sourceFile(), suggestion.edits(), List.of())
                        .success(),
                "R021(ピリオド終端) 修正後ソースが再パースできること");
    }
}
