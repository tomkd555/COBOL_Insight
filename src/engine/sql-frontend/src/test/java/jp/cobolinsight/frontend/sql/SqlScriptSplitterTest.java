package jp.cobolinsight.frontend.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verification of splitting an SQL script into statements ({@link SqlScriptSplitter}). */
class SqlScriptSplitterTest {

    private static List<String> textsOf(String script) {
        return textsOf(script, index -> index);
    }

    private static List<String> textsOf(String script, IntUnaryOperator byteOffsetOfChar) {
        return SqlScriptSplitter.split(script, byteOffsetOfChar).stream()
                .map(statement -> statement.text().replaceAll("\\s+", " ").strip()).toList();
    }

    @Test
    void everyStatementEndsAtItsSemicolon() {
        assertEquals(List.of("SELECT COL FROM T", "UPDATE T SET COL = 1"),
                textsOf("SELECT COL FROM T;\nUPDATE T SET COL = 1;\n"));
    }

    /** The line a statement starts on is the line of its own first character, not of the banner. */
    @Test
    void leadingCommentLinesAreNotPartOfTheStatement() {
        List<SqlScriptSplitter.Statement> statements = SqlScriptSplitter.split("""
                -- 口座マスタを作る。
                -- 主キーは口座番号。
                CREATE TABLE T
                    ( C CHAR(1) NOT NULL
                    );
                """);
        assertEquals(1, statements.size());
        assertEquals(3, statements.get(0).startLine());
        assertEquals(1, statements.get(0).startColumn());
        assertEquals(5, statements.get(0).endLine());
    }

    @Test
    void semicolonInsideALiteralOrACommentEndsNothing() {
        assertEquals(List.of("INSERT INTO T VALUES ('A;B')", "COMMIT"),
                textsOf("""
                        INSERT INTO T VALUES ('A;B')  -- 注記に ; を書く
                        ;
                        COMMIT;
                        """));
        assertEquals(List.of("SELECT C /* 途中の注記 ; */ FROM T"),
                textsOf("SELECT C /* 途中の注記 ; */ FROM T;\n"));
    }

    /**
     * A compound routine is one statement. The semicolons of its body, of the IF inside it and of
     * the nested block all stand at a depth the terminator does not reach.
     */
    @Test
    void aCompoundRoutineIsOneStatement() {
        List<String> statements = textsOf("""
                CREATE PROCEDURE P (IN A INTEGER)
                    LANGUAGE SQL
                    BEGIN
                        DECLARE V INTEGER DEFAULT 0;
                        IF A = 0 THEN
                            SET V = 1;
                        ELSE
                            BEGIN
                                UPDATE T SET C = A;
                            END;
                        END IF;
                    END;
                GRANT SELECT ON TABLE T TO PUBLIC;
                """);
        assertEquals(2, statements.size(), () -> "実際は " + statements);
        assertEquals("GRANT SELECT ON TABLE T TO PUBLIC", statements.get(1));
    }

    /** A trigger's FOR EACH ROW is not a loop, so nothing is left open when its body closes. */
    @Test
    void aTriggerBodyClosesWithItsEnd() {
        assertEquals(2, textsOf("""
                CREATE TRIGGER G AFTER UPDATE OF C ON T
                    REFERENCING OLD AS O NEW AS N
                    FOR EACH ROW MODE DB2SQL
                    BEGIN ATOMIC
                        INSERT INTO A (C) VALUES (N.C);
                    END;
                COMMIT;
                """).size());
    }

    /** A CASE expression closes with a bare END, and the statement holding it ends after that. */
    @Test
    void aCaseExpressionDoesNotEndTheStatement() {
        assertEquals(List.of("UPDATE T SET C = CASE WHEN C = 1 THEN 2 ELSE 3 END", "COMMIT"),
                textsOf("""
                        UPDATE T SET C = CASE WHEN C = 1 THEN 2 ELSE 3 END;
                        COMMIT;
                        """));
    }

    /** After a TERMINATOR directive, the named character ends a statement and the semicolon does not. */
    @Test
    void theTerminatorDirectiveIsHonoured() {
        assertEquals(List.of("CREATE PROCEDURE P LANGUAGE SQL BEGIN UPDATE T SET C = 1; END",
                        "SELECT C FROM T"),
                textsOf("""
                        --#SET TERMINATOR @
                        CREATE PROCEDURE P
                            LANGUAGE SQL
                            BEGIN
                                UPDATE T SET C = 1;
                            END@
                        SELECT C FROM T@
                        """));
    }

    /** An END takes its suffix with it: reading the CASE of END CASE would open the block again. */
    @Test
    void endCaseClosesOneBlockAndOpensNone() {
        List<String> statements = textsOf("""
                CREATE PROCEDURE P (IN C INTEGER)
                    LANGUAGE SQL
                    BEGIN
                        DECLARE V INTEGER DEFAULT 0;
                        CASE C
                            WHEN 1 THEN SET V = 1;
                            ELSE SET V = 2;
                        END CASE;
                    END;
                GRANT SELECT ON TABLE T TO PUBLIC;
                """);
        assertEquals(2, statements.size(), () -> "実際は " + statements);
        assertEquals("GRANT SELECT ON TABLE T TO PUBLIC", statements.get(1));
    }

    /** A comment after the last terminator is a comment, not a statement of its own. */
    @Test
    void aCommentAfterTheLastStatementIsNoStatement() {
        assertEquals(List.of("SELECT C FROM T"), textsOf("""
                SELECT C FROM T;
                /* 後書きの注記。ここで終わる。 */
                """));
        assertEquals(List.of("SELECT C FROM T"), textsOf("SELECT C FROM T;\n-- 後書きの注記\n"));
    }

    /** A block comment above a statement is a banner: the statement starts on its own line. */
    @Test
    void aBlockCommentBannerIsNotPartOfTheStatement() {
        List<SqlScriptSplitter.Statement> statements = SqlScriptSplitter.split("""
                /* 口座マスタを作る。
                   主キーは口座番号。 */
                CREATE TABLE T
                    ( C CHAR(1) NOT NULL
                    );
                """);
        assertEquals(1, statements.size());
        assertEquals(3, statements.get(0).startLine());
        assertTrue(statements.get(0).text().startsWith("CREATE TABLE T"),
                statements.get(0).text());
    }

    /** The sequence number of a card image belongs to no statement. */
    @Test
    void theSequenceAreaOfACardImageIsDropped() {
        assertEquals(List.of("SELECT COUNT(*) FROM T", "COMMIT"), textsOf(String.join("\n",
                card("SELECT COUNT(*)", "00000100"),
                card("  FROM T;", "00000200"),
                card("COMMIT;", "00000300"),
                "")));
    }

    /**
     * A card image counts its columns in bytes. A Shift_JIS member whose statement holds a Japanese
     * literal reaches column 73 with fewer characters than a Latin one, and measuring in characters
     * would leave half the sequence number inside the statement.
     */
    @Test
    void theSequenceAreaIsMeasuredInBytes() {
        Charset shiftJis = Charset.forName("Shift_JIS");
        String script = String.join("\n",
                card("SELECT C FROM T WHERE C = '取引明細'", "00000100", shiftJis),
                card("   AND K = 1;", "00000200", shiftJis),
                "");
        assertEquals(List.of("SELECT C FROM T WHERE C = '取引明細' AND K = 1"),
                textsOf(script, byteOffsets(script, shiftJis)));
    }

    /**
     * The card-image decision belongs to the file. A free-format script that happens to write a
     * number at the end of a 75-character line is not a card image, and cutting that line at
     * column 73 would take the number out of the statement.
     */
    @Test
    void aFreeFormatLineEndingInDigitsKeepsThem() {
        String script = "UPDATE T\n" + "   SET C = ".concat(" ".repeat(61)) + "123\n   ;\n";
        assertEquals(75, script.split("\n")[1].length(), "2 行目は 75 文字であること");
        assertEquals(List.of("UPDATE T SET C = 123"), textsOf(script));
    }

    private static String card(String code, String sequence) {
        return card(code, sequence, StandardCharsets.ISO_8859_1);
    }

    /** One card image: the text in byte columns 1-72 and the sequence number in 73-80. */
    private static String card(String code, String sequence, Charset charset) {
        return code + " ".repeat(72 - code.getBytes(charset).length) + sequence;
    }

    /** The byte offset of each character of a script written in one code page. */
    private static IntUnaryOperator byteOffsets(String script, Charset charset) {
        int[] offsets = new int[script.length() + 1];
        for (int i = 0; i < script.length(); i++) {
            offsets[i + 1] =
                    offsets[i] + String.valueOf(script.charAt(i)).getBytes(charset).length;
        }
        return index -> offsets[index];
    }

    /**
     * The body of a compound routine ends at the END that matches its BEGIN, whatever that END
     * carries after it, and a block nested inside comes back whole for the caller to open the same
     * way.
     */
    @Test
    void theCompoundBodyEndsAtTheEndThatMatchesItsBegin() {
        SqlScriptSplitter.CompoundBody body = SqlScriptSplitter.compoundBody("""
                CREATE PROCEDURE P LANGUAGE SQL
                    BEGIN ATOMIC
                        DECLARE V INTEGER;
                        BEGIN
                            UPDATE T SET C = 1;
                        END;
                        CASE V WHEN 1 THEN SET V = 2; ELSE SET V = 3; END CASE;
                    END L1""").orElseThrow();
        assertEquals(1, body.lineOffset(), "本体は CREATE の 1 行下から始まること");
        assertTrue(body.text().contains("END CASE"), body.text());
        assertFalse(body.text().contains("END L1"), body.text());
        assertEquals(List.of("DECLARE V INTEGER",
                        "BEGIN UPDATE T SET C = 1; END",
                        "CASE V WHEN 1 THEN SET V = 2; ELSE SET V = 3; END CASE"),
                textsOf(body.text()));
    }

    /** A CASE statement holds statements, so its branches are opened the way a BEGIN block is. */
    @Test
    void theBranchesOfACaseStatementAreItsBody() {
        SqlScriptSplitter.CompoundBody body = SqlScriptSplitter.compoundBody(
                "CASE V WHEN 1 THEN UPDATE T1 SET C = 1; ELSE UPDATE T2 SET C = 2; END CASE")
                .orElseThrow();

        assertEquals(List.of("V WHEN 1 THEN UPDATE T1 SET C = 1", "ELSE UPDATE T2 SET C = 2"),
                textsOf(body.text()));
    }

    /** A CASE standing inside a statement is the expression form: its branches are values. */
    @Test
    void aCaseExpressionInsideAStatementOpensNoBody() {
        assertTrue(SqlScriptSplitter.compoundBody("SET V = CASE WHEN A = 1 THEN 2 ELSE 3 END")
                .isEmpty());
    }

    /** An unclosed literal blanks the rest of the script, so the line it opens on is reported. */
    @Test
    void anUnclosedLiteralNamesTheLineItOpensOn() {
        assertEquals(3, SqlScriptSplitter.unclosedLiteralLine("""
                SELECT C FROM T;
                COMMIT;
                INSERT INTO T VALUES ('A);
                COMMIT;
                """).orElseThrow());
        assertTrue(SqlScriptSplitter.unclosedLiteralLine(
                "INSERT INTO T VALUES ('A');\n-- it's closed\n").isEmpty());
    }

    /** A statement that is no compound has no body, which is how a RETURN function is told apart. */
    @Test
    void aStatementWithNoBlockHasNoCompoundBody() {
        assertTrue(SqlScriptSplitter.compoundBody(
                "CREATE FUNCTION F () RETURNS INTEGER LANGUAGE SQL RETURN 1").isEmpty());
    }

    /** A script whose last statement carries no terminator still holds that statement. */
    @Test
    void aStatementWithoutATerminatorIsStillRead() {
        assertEquals(List.of("SELECT C FROM T"), textsOf("SELECT C FROM T\n"));
    }
}
