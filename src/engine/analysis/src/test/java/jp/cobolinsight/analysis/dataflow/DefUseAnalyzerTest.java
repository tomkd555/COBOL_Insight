package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文種別ごとの def/use 抽出と、予約語・リテラル・数値の除外を検証する。 */
class DefUseAnalyzerTest {

    private static SimpleStatement simple(String verb, String text) {
        SourcePosition pos = SourcePosition.fileStart("t.cbl");
        return new SimpleStatement(verb, text, new SourceRange(pos, pos));
    }

    private static DefUse extract(String verb, String text) {
        return DefUseAnalyzer.extract(simple(verb, text));
    }

    @Test
    void moveSplitsSenderAndReceiver() {
        DefUse du = extract("MOVE", "MOVE WS-A TO WS-B");
        assertEquals(Set.of("WS-B"), du.defs());
        assertEquals(Set.of("WS-A"), du.uses());
    }

    @Test
    void moveExcludesLiteralAndFigurativeConstant() {
        assertEquals(Set.of(), extract("MOVE", "MOVE 100 TO WS-A").uses());
        assertEquals(Set.of("WS-A"), extract("MOVE", "MOVE 100 TO WS-A").defs());
        assertEquals(Set.of(), extract("MOVE", "MOVE SPACES TO WS-A").uses());
    }

    @Test
    void moveToMultipleReceivers() {
        DefUse du = extract("MOVE", "MOVE WS-A TO WS-B WS-C");
        assertEquals(Set.of("WS-B", "WS-C"), du.defs());
        assertEquals(Set.of("WS-A"), du.uses());
    }

    @Test
    void moveKeepsSubscriptAsUse() {
        DefUse du = extract("MOVE", "MOVE WS-A TO WS-TBL(WS-IDX)");
        assertEquals(Set.of("WS-TBL"), du.defs());
        assertTrue(du.uses().contains("WS-A"));
        assertTrue(du.uses().contains("WS-IDX"));
    }

    @Test
    void computeTargetIsDefRestAreUses() {
        DefUse du = extract("COMPUTE", "COMPUTE WS-C = WS-A + WS-B * 2");
        assertEquals(Set.of("WS-C"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void computeIgnoresSizeErrorHandler() {
        DefUse du = extract("COMPUTE", "COMPUTE WS-C = WS-A / WS-B ON SIZE ERROR MOVE 0 TO WS-ERR");
        assertEquals(Set.of("WS-C"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void addToAccumulatesReceiver() {
        DefUse du = extract("ADD", "ADD WS-A TO WS-B");
        assertEquals(Set.of("WS-B"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void addGivingTargetsResult() {
        DefUse du = extract("ADD", "ADD WS-A WS-B GIVING WS-C");
        assertEquals(Set.of("WS-C"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void subtractFromReceiver() {
        DefUse du = extract("SUBTRACT", "SUBTRACT WS-A FROM WS-B");
        assertEquals(Set.of("WS-B"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void divideGivingWithRemainder() {
        DefUse du = extract("DIVIDE", "DIVIDE WS-A BY WS-B GIVING WS-Q REMAINDER WS-R");
        assertEquals(Set.of("WS-Q", "WS-R"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void initializeDefinesItems() {
        DefUse du = extract("INITIALIZE", "INITIALIZE WS-A WS-B");
        assertEquals(Set.of("WS-A", "WS-B"), du.defs());
        assertEquals(Set.of(), du.uses());
    }

    @Test
    void setDefinesIndex() {
        DefUse du = extract("SET", "SET WS-IDX TO 1");
        assertEquals(Set.of("WS-IDX"), du.defs());
        assertEquals(Set.of(), du.uses());
    }

    @Test
    void acceptDefinesReceiver() {
        DefUse du = extract("ACCEPT", "ACCEPT WS-IN FROM DATE");
        assertEquals(Set.of("WS-IN"), du.defs());
        assertEquals(Set.of(), du.uses());
    }

    @Test
    void readIntoDefinesRecordIgnoringFileName() {
        DefUse du = extract("READ", "READ ZAIKO-FILE INTO WS-REC");
        assertEquals(Set.of("WS-REC"), du.defs());
        assertEquals(Set.of(), du.uses());
    }

    @Test
    void callArgumentsAreUsesReturningIsDef() {
        DefUse du = extract("CALL", "CALL 'SUB001' USING WS-A WS-B RETURNING WS-R");
        assertEquals(Set.of("WS-R"), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void displayIsUseOnly() {
        DefUse du = extract("DISPLAY", "DISPLAY WS-A WS-B");
        assertEquals(Set.of(), du.defs());
        assertEquals(Set.of("WS-A", "WS-B"), du.uses());
    }

    @Test
    void handlesJapaneseDataNames() {
        DefUse du = extract("MOVE", "MOVE WS-受注件数 TO WS-合計");
        assertEquals(Set.of("WS-合計"), du.defs());
        assertEquals(Set.of("WS-受注件数"), du.uses());
    }

    @Test
    void externalInputTargetForAccept() {
        assertEquals(Set.of("WS-IN"),
                DefUseAnalyzer.externalInputTargets(simple("ACCEPT", "ACCEPT WS-IN")));
        assertEquals(Set.of(),
                DefUseAnalyzer.externalInputTargets(simple("MOVE", "MOVE WS-A TO WS-B")));
    }
}
