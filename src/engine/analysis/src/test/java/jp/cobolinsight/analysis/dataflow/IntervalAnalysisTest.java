package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.cfg.ControlFlowGraph;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.ValueInterval;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit verification of the interval value-range analysis (intervalAt). Confirms, using small
 * sources parsed with the real parser: the point interval of a literal assignment, the interval
 * of an addition, the range of a VARYING loop, widening convergence for a monotonically
 * increasing counter, UNTIL narrowing, the grounds for R005 (out-of-OCCURS-range subscript
 * access), and the grounds for R028 (a negative value computed into an unsigned item).
 */
class IntervalAnalysisTest {

    private static ValueInterval intervalAt(String src, String atSubstring, String var) {
        CobolSemanticModel model = InlinePrograms.parse(src);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);
        CfgNode n = InlinePrograms.nodeContaining(cfg, atSubstring);
        return df.intervalAt(n, var).orElseThrow(
                () -> new AssertionError(var + " の区間が " + atSubstring + " で追跡外である"));
    }

    // ---- literal assignment, MOVE, addition ----

    private static final String ARITH = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. ARITH.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-A  PIC 9(04).",
            "       01  WS-B  PIC 9(04).",
            "       01  WS-C  PIC 9(06).",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           MOVE 5 TO WS-A.",
            "           MOVE WS-A TO WS-B.",
            "           ADD 3 TO WS-B.",
            "           ADD WS-A TO WS-B GIVING WS-C.",
            "           DISPLAY WS-C.",
            "           STOP RUN.");

    @Test
    void literalMoveGivesPointInterval() {
        assertEquals(ValueInterval.point(5), intervalAt(ARITH, "MOVE WS-A TO WS-B", "WS-A"),
                "MOVE 5 TO WS-A の後、WS-A は点区間 [5,5]");
    }

    @Test
    void additionAccumulatesInterval() {
        assertEquals(ValueInterval.point(8),
                intervalAt(ARITH, "ADD WS-A TO WS-B GIVING WS-C", "WS-B"),
                "MOVE WS-A(5) 後の ADD 3 TO WS-B で WS-B は [8,8]");
    }

    @Test
    void addGivingComputesSum() {
        assertEquals(ValueInterval.point(13), intervalAt(ARITH, "DISPLAY WS-C", "WS-C"),
                "ADD WS-A(5) TO WS-B(8) GIVING WS-C で WS-C は [13,13]");
    }

    // ---- paragraph PERFORM VARYING: path where FROM/BY/UNTIL remain in the statement text + narrowing ----

    private static final String VARY_PARA = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. VARYP.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-TBL.",
            "           05  WS-ELEM  PIC 9(04) OCCURS 10.",
            "       01  WS-IDX  PIC 9(04).",
            "       01  WS-SUM  PIC 9(06) VALUE ZERO.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM 100-SUM",
            "               VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10.",
            "           STOP RUN.",
            "       100-SUM.",
            "           ADD WS-ELEM(WS-IDX) TO WS-SUM.");

    @Test
    void paragraphVaryingNarrowsToDeclaredRange() {
        ValueInterval idx = intervalAt(VARY_PARA, "ADD WS-ELEM(WS-IDX) TO WS-SUM", "WS-IDX");
        assertEquals(ValueInterval.of(1, 10), idx,
                "FROM 1 BY 1 UNTIL WS-IDX > 10 で本体の WS-IDX は widening 後 narrowing で [1,10]");
        assertFalse(idx.mayExceed(10), "OCCURS 10 を超えない(偽陽性を出さない)");
    }

    // ---- inline PERFORM VARYING: the case that stays within OCCURS and the case that may exceed it ----

    private static final String INLINE_SAFE = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. INSAFE.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-TBL.",
            "           05  WS-ELEM  PIC 9(04) OCCURS 10.",
            "       01  WS-IDX  PIC 9(04).",
            "       01  WS-SUM  PIC 9(06) VALUE ZERO.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM VARYING WS-IDX FROM 1 BY 1",
            "                   UNTIL WS-IDX > 10",
            "               ADD WS-ELEM(WS-IDX) TO WS-SUM",
            "           END-PERFORM.",
            "           STOP RUN.");

    @Test
    void inlineVaryingWithinOccursDoesNotExceed() {
        ValueInterval idx = intervalAt(INLINE_SAFE, "ADD WS-ELEM(WS-IDX) TO WS-SUM", "WS-IDX");
        assertEquals(ValueInterval.of(1, 10), idx, "UNTIL WS-IDX > 10 で本体の WS-IDX は [1,10]");
        assertFalse(idx.mayExceed(10), "OCCURS 10 を超えない");
    }

    private static final String INLINE_UNSAFE = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. INUNSAFE.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-TBL.",
            "           05  WS-ELEM  PIC 9(04) OCCURS 10.",
            "       01  WS-IDX  PIC 9(04).",
            "       01  WS-CNT  PIC 9(04).",
            "       01  WS-SUM  PIC 9(06) VALUE ZERO.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           ACCEPT WS-CNT.",
            "           PERFORM VARYING WS-IDX FROM 1 BY 1",
            "                   UNTIL WS-IDX > WS-CNT",
            "               ADD WS-ELEM(WS-IDX) TO WS-SUM",
            "           END-PERFORM.",
            "           STOP RUN.");

    @Test
    void inlineVaryingWithInputBoundMayExceedOccurs() {
        ValueInterval idx = intervalAt(INLINE_UNSAFE, "ADD WS-ELEM(WS-IDX) TO WS-SUM", "WS-IDX");
        assertTrue(idx.hiUnbounded(), "入力由来 WS-CNT の上限は非有界のため WS-IDX の上端も非有界");
        assertTrue(idx.mayExceed(10), "OCCURS 10 を超え得る(R005 相当)");
    }

    // ---- monotonically increasing counter: widening converges to [lower bound, +infinity) and terminates ----

    private static final String COUNTER = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. COUNTER.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-CNT   PIC 9(04) VALUE ZERO.",
            "       01  WS-FLAG  PIC X     VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM UNTIL WS-FLAG = 'Y'",
            "               ADD 1 TO WS-CNT",
            "           END-PERFORM.",
            "           DISPLAY WS-CNT.",
            "           STOP RUN.");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void monotonicCounterWidensToLowerBoundedUnbounded() {
        ValueInterval cnt = intervalAt(COUNTER, "DISPLAY WS-CNT", "WS-CNT");
        assertFalse(cnt.loUnbounded(), "下端は有界(初期値 0)");
        assertEquals(0L, cnt.lo(), "下端は 0");
        assertTrue(cnt.hiUnbounded(), "終了条件が上限を絞らないため上端は widening で +∞");
    }

    // ---- when a monotonically increasing counter is used as a subscript: it may exceed OCCURS ----

    private static final String COUNTER_SUBSCRIPT = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. CNTSUB.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-TBL.",
            "           05  WS-ERR  PIC 9(04) OCCURS 20.",
            "       01  WS-CNT   PIC 9(04) VALUE ZERO.",
            "       01  WS-FLAG  PIC X     VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM UNTIL WS-FLAG = 'Y'",
            "               ADD 1 TO WS-CNT",
            "               MOVE 100 TO WS-ERR(WS-CNT)",
            "           END-PERFORM.",
            "           STOP RUN.");

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void monotonicCounterSubscriptMayExceedOccurs() {
        ValueInterval cnt =
                intervalAt(COUNTER_SUBSCRIPT, "MOVE 100 TO WS-ERR(WS-CNT)", "WS-CNT");
        assertTrue(cnt.mayExceed(20),
                "ADD 1 の単調増加カウンタは上限検査が無く OCCURS 20 を超え得る(R005 相当)");
    }

    // ---- a negative value computed into an unsigned receiving item ----

    private static final String NEGATIVE = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. NEGCMP.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-A  PIC 9(03) VALUE 5.",
            "       01  WS-B  PIC 9(03) VALUE 10.",
            "       01  WS-C  PIC 9(03).",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           SUBTRACT WS-B FROM WS-A GIVING WS-C.",
            "           DISPLAY WS-C.",
            "           STOP RUN.");

    @Test
    void unsignedReceiverGetsNegativeComputedValue() {
        ValueInterval c = intervalAt(NEGATIVE, "DISPLAY WS-C", "WS-C");
        assertEquals(ValueInterval.point(-5), c,
                "SUBTRACT WS-B(10) FROM WS-A(5) GIVING WS-C は [-5,-5]");
        assertTrue(c.mayBeNegative(), "符号なし WS-C に負値が算出され得る(R028 相当)");
    }

    // ---- decimal literals ----

    private static final String DECIMAL = InlinePrograms.source(
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID. DECLIT.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-RATE   PIC 9V99.",
            "       01  WS-TOTAL  PIC 9(04) VALUE 10.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           MOVE .25 TO WS-RATE.",
            "           ADD 1.50 TO WS-TOTAL.",
            "           DISPLAY WS-RATE.",
            "           STOP RUN.");

    @Test
    void decimalLiteralIsNotReadAsItsDigitRun() {
        assertEquals(ValueInterval.unbounded(),
                intervalAt(DECIMAL, "ADD 1.50 TO WS-TOTAL", "WS-RATE"),
                "MOVE .25 TO WS-RATE は [25,25] ではなく上下限なし");
        assertEquals(ValueInterval.unbounded(),
                intervalAt(DECIMAL, "DISPLAY WS-RATE", "WS-TOTAL"),
                "ADD 1.50 TO WS-TOTAL は 1 と 50 を足さず上下限なし");
    }

    // ---- untracked variables yield empty ----

    @Test
    void untrackedVariableReturnsEmpty() {
        CobolSemanticModel model = InlinePrograms.parse(ARITH);
        ControlFlowGraph cfg = CfgBuilder.build(model);
        ProgramDataFlow df = DataFlowEngine.analyze(model, cfg);
        CfgNode first = InlinePrograms.nodeContaining(cfg, "MOVE 5 TO WS-A");
        assertTrue(df.intervalAt(first, "WS-C").isEmpty(),
                "代入前に到達しない WS-C は追跡外で empty");
    }
}
