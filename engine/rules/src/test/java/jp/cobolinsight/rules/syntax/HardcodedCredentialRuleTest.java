package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R026 ハードコードされたパスワード・認証情報の合成fixture検証(テキスト走査のみで判定する)。 */
class HardcodedCredentialRuleTest {

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX026.",
            /*  3 */ "       DATA DIVISION.",
            /*  4 */ "       WORKING-STORAGE SECTION.",
            /*  5 */ "       01  API-KEY                     PIC X(10) VALUE 'AK-123'.",
            /*  6 */ "       01  WS-TOKENIZED-FLAG           PIC X(01) VALUE 'Y'.",
            /*  7 */ "       01  WS-CURRENT-TOKEN            PIC X(10) VALUE 'T-99'.",
            /*  8 */ "       PROCEDURE DIVISION.",
            /*  9 */ "       0000-MAIN.",
            /* 10 */ "           MOVE 'ABCDEF' TO TOKEN",
            /* 11 */ "           MOVE SPACES TO API-KEY",
            /* 12 */ "      * PASSWORD 'COMMENT' コメント行は対象外",
            /* 13 */ "           MOVE 'U1' TO USR-ID MOVE 'P2' TO TOKEN",
            /* 14 */ "           MOVE 'K1' TO",
            /* 15 */ "      -        TOKEN",
            /* 16 */ "           MOVE SPACES TO WS-X *> TOKEN 'NOTE'",
            /* 17 */ "           MOVE ID-1 TO TOKEN" + " ".repeat(43) + "'ZZZ'",
            /* 18 */ "           GOBACK.",
            "");

    @Test
    void detectsCredentialLiteralsInValueClauseAndMoveStatement() {
        List<Finding> findings = new HardcodedCredentialRule()
                .evaluate(Fixtures.context(List.of(), Map.of("FIX026.cbl", SOURCE)));

        assertEquals(List.of(5, 7, 10, 13, 13, 14),
                findings.stream().map(f -> f.location().line()).sorted().toList(),
                () -> "検出: " + findings);
        for (Finding finding : findings) {
            assertEquals("R026", finding.ruleId());
            assertEquals(FindingLevel.ERROR, finding.level());
            assertEquals("FIX026.cbl", finding.location().file());
        }
        assertTrue(findings.stream().noneMatch(f -> f.location().line() == 6),
                "WS-TOKENIZED-FLAG のような語中の部分一致は検出しないこと");
        assertTrue(findings.stream().anyMatch(f -> f.location().line() == 7),
                "WS-CURRENT-TOKEN のようなセグメント一致の識別子は検出すること");
        assertTrue(findings.stream().noneMatch(f -> f.location().line() == 11),
                "文字列リテラルの無い行は検出しないこと");
        assertTrue(findings.stream().noneMatch(f -> f.location().line() == 12),
                "コメント行は検出しないこと");
        assertTrue(findings.stream().noneMatch(f -> f.location().line() == 16),
                "*> 行内コメント内のリテラルは検出しないこと");
        assertTrue(findings.stream().noneMatch(f -> f.location().line() == 17),
                "73〜80桁の識別領域にあるリテラルは検出しないこと");
    }

    @Test
    void reportsEveryLiteralOnAKeywordLine() {
        List<Finding> findings = new HardcodedCredentialRule()
                .evaluate(Fixtures.context(List.of(), Map.of("FIX026.cbl", SOURCE)));

        assertEquals(2, findings.stream().filter(f -> f.location().line() == 13).count(),
                "キーワード行の全リテラルをそれぞれ検出すること");
    }

    @Test
    void joinsContinuationLineIntoPrecedingLine() {
        List<Finding> findings = new HardcodedCredentialRule()
                .evaluate(Fixtures.context(List.of(), Map.of("FIX026.cbl", SOURCE)));

        assertEquals(1, findings.stream().filter(f -> f.location().line() == 14).count(),
                "7桁目'-'の継続行を結合し、開始行の行番号で検出すること");
    }
}
