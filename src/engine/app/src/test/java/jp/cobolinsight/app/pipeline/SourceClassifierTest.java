package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification of classifying kind from content ({@link SourceClassifier}). Since it is a pure
 * function with no file I/O, both real byte sequences from samples and hand-built byte sequences
 * are fed to it directly.
 */
class SourceClassifierTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static byte[] sample(String relPath) {
        try {
            return Files.readAllBytes(SAMPLES.resolve(relPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static AssetKind kindOf(byte[] content) {
        return SourceClassifier.classify(content).kind();
    }

    @Test
    void realSamplesAreClassifiedByContent() {
        assertEquals(AssetKind.COBOL, kindOf(sample("cobol/SYK001.cbl")));
        assertEquals(AssetKind.COBOL, kindOf(sample("cobol/SYK008.cbl")));
        assertEquals(AssetKind.COPYBOOK, kindOf(sample("copybook/SYKCPY1.cpy")));
        assertEquals(AssetKind.COPYBOOK, kindOf(sample("copybook/SYKCPY2.cpy")));
        assertEquals(AssetKind.COPYBOOK, kindOf(sample("copybook/SYKCPY3.cpy")));
        assertEquals(AssetKind.JCL, kindOf(sample("jcl/SYKD010.jcl")));
        assertEquals(AssetKind.JCL, kindOf(sample("jcl/SYKD020.jcl")));
        assertEquals(AssetKind.BMS, kindOf(sample("bms/SYKMAP1.bms")));
    }

    @Test
    void bothEncodingSamplesAreCobolRegardlessOfCharset() {
        assertEquals(AssetKind.COBOL, kindOf(sample("encoding/SYKENC1_UTF8.cbl")));
        assertEquals(AssetKind.COBOL, kindOf(sample("encoding/SYKENC1_SJIS.cbl")),
                "Shift_JIS のソースも判定できること");
    }

    /**
     * Plain prose alone must not decide a kind. A document that happens to place COBOL words
     * within the right columns is cut off by excluding {@code .md} from the candidates (the
     * excluded extension in {@link SourceDiscovery}).
     */
    @Test
    void proseWithoutMarkersStaysUndecided() {
        byte[] content = utf8("""
                本書は samples/ 配下に作成した模擬資産の正解データである。
                混入した欠陥の一覧と、呼出関係の正解を記す。

                | ファイル | 内容 |
                |---|---|
                | SYK001.cbl | 受注データ検証 |
                """);
        assertNull(kindOf(content));
    }

    @Test
    void commentOnlyProgramIdIsNotEvidence() {
        // Same shape as the comment lines in samples/cobol/SYK001.cbl. If a line whose column 7
        // is * were treated as body text, comments alone would make it pass as COBOL.
        byte[] content = utf8("""
                      *================================================*
                      *  PROGRAM-ID : SYK001                           *
                      *  IDENTIFICATION DIVISION は注記の中にしかない   *
                      *================================================*
                """);
        assertNull(kindOf(content));
    }

    @Test
    void jclCommentLinesAreSkipped() {
        byte[] content = utf8("""
                //*  これは注記であり JOB 文ではない
                /*
                //SYKD010  JOB  (SYK1234),'TEST',CLASS=A
                """);
        assertEquals(AssetKind.JCL, kindOf(content));
    }

    @Test
    void jclWithoutNameFieldIsRecognized() {
        assertEquals(AssetKind.JCL, kindOf(utf8("//         SET CYCLE=250718\n")));
    }

    @Test
    void bmsIsRecognizedAfterAsteriskComments() {
        byte[] content = utf8("""
                *----------------------------------------------------------------*
                * MAPSET SYKMAP1
                *----------------------------------------------------------------*

                SYKMAP1  DFHMSD TYPE=&SYSPARM,                                         X
                               MODE=INOUT
                """);
        assertEquals(AssetKind.BMS, kindOf(content));
    }

    @Test
    void copybookIsDecidedByLevelNumberWhenNoDivisionAppears() {
        byte[] content = utf8("""
                      *  受注レコード
                       01  SYK1-ORDER-RECORD.
                           05  SYK1-ORDER-NO      PIC X(10).
                           05  SYK1-QTY           PIC 9(05) COMP-3.
                """);
        assertEquals(AssetKind.COPYBOOK, kindOf(content));
    }

    @Test
    void procedureFragmentWithoutLevelNumberOrDivisionStaysUndecided() {
        byte[] content = utf8("""
                           MOVE ZERO TO WS-COUNTER
                           PERFORM VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 10
                               ADD 1 TO WS-COUNTER
                           END-PERFORM
                """);
        SourceClassifier.Verdict verdict = SourceClassifier.classify(content);
        assertFalse(verdict.decided());
        assertFalse(verdict.binary(), "テキストである以上バイナリ判定にはしないこと");
    }

    /**
     * A DDL member. The banner above the first statement is written with {@code --}, which is a
     * comment and must not be read as the first significant line.
     */
    @Test
    void ddlIsRecognizedAfterDashComments() {
        byte[] content = utf8("""
                -- 口座マスタ。
                CREATE TABLE CSDB.CSQKOZA
                    ( KOZA_NO   CHAR(10) NOT NULL,
                      PRIMARY KEY (KOZA_NO)
                    );
                """);
        assertEquals(AssetKind.SQL, kindOf(content));
    }

    /**
     * The same member with a {@code /* *}{@code /} banner. The continuation lines of the banner are
     * comment too, so the first significant line is the statement under it.
     */
    @Test
    void ddlIsRecognizedAfterAMultiLineBlockComment() {
        byte[] content = utf8("""
                /* 口座マスタを作る
                   2026-09 改訂 */
                CREATE TABLE CSDB.CSQKOZA
                    ( KOZA_NO   CHAR(10) NOT NULL,
                      PRIMARY KEY (KOZA_NO)
                    );
                """);
        assertEquals(AssetKind.SQL, kindOf(content));
    }

    /** A SPUFI member: DML in a card image, its sequence number in columns 73-80. */
    @Test
    void spufiInputIsRecognized() {
        byte[] content = utf8(String.join("\n",
                padded("-- 件数を数える", "00000100"),
                padded("SELECT COUNT(*)", "00000200"),
                padded("  FROM CSDB.CSQKOZA;", "00000300"),
                ""));
        assertEquals(AssetKind.SQL, kindOf(content));
    }

    /** One card image: the text in columns 1-72 and the sequence number in 73-80. */
    private static String padded(String code, String sequence) {
        return code + " ".repeat(72 - code.length()) + sequence;
    }

    /**
     * Content decides, so a file carrying a COBOL main body is COBOL however its name ends. The
     * extension only names the candidate; {@link SourceDiscovery} reports the disagreement.
     */
    @Test
    void cobolInsideAnSqlFileStaysCobol() {
        byte[] content = utf8("""
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID.  SYKSQL1.
                       PROCEDURE DIVISION.
                       MAIN-RTN.
                           GOBACK.
                """);
        assertEquals(AssetKind.COBOL, kindOf(content));
    }

    /** The same for JCL: its // statement decides before the end of the file is reached. */
    @Test
    void jclInsideAnSqlFileStaysJcl() {
        byte[] content = utf8("""
                //CSLD010 JOB (ACCT),'DDL',CLASS=A
                //STEP010  EXEC PGM=IKJEFT01
                //SYSTSIN  DD *
                  DSN SYSTEM(DB2P)
                  RUN PROGRAM(DSNTEP2)
                /*
                """);
        assertEquals(AssetKind.JCL, kindOf(content));
    }

    /**
     * A COBOL procedure fragment whose first statement is a verb that is an SQL keyword too must
     * not pass as an SQL script. SET, CALL, DELETE and MERGE are verbs of both languages.
     */
    @Test
    void cobolVerbSharedWithSqlDoesNotMakeAScript() {
        byte[] content = utf8("""
                           SET WS-FLAG TO TRUE
                           CALL 'SYK002' USING WS-AREA
                """);
        SourceClassifier.Verdict verdict = SourceClassifier.classify(content);
        assertFalse(verdict.decided(), "COBOL の手続き部の断片を SQL と読まないこと");
    }

    /**
     * The same fragment written from column 1, where a script writes its statements. The keyword
     * alone does not decide it: the file also has to end a statement somewhere, and this one never
     * does.
     */
    @Test
    void cobolFragmentInColumnOneDoesNotMakeAScript() {
        byte[] content = utf8("""
                SET WS-FLAG TO TRUE
                CALL 'SYK002' USING WS-AREA
                """);
        assertFalse(SourceClassifier.classify(content).decided(),
                "終端子のない断片を SQL と読まないこと");
    }

    /** An IDCAMS control card member opens with DELETE or SET and ends no statement. */
    @Test
    void idcamsControlCardsAreNotAScript() {
        byte[] content = utf8("""
                 DELETE FLW.SYKT.ORDER.DAILY
                 SET MAXCC = 0
                 DEFINE CLUSTER (NAME(FLW.SYKT.ORDER.DAILY) -
                        INDEXED KEYS(8 0))
                """);
        assertFalse(SourceClassifier.classify(content).decided(),
                "IDCAMS の制御文を SQL と読まないこと");
    }

    /** A batch script opens with SET as well, and its extension names no kind either. */
    @Test
    void aBatchScriptIsNotAScript() {
        byte[] content = utf8("""
                SET DSN=SYKT.ORDER.DAILY
                ECHO %DSN%
                """);
        assertFalse(SourceClassifier.classify(content).decided(),
                "バッチファイルを SQL と読まないこと");
    }

    /** A query opening with a common table expression is a script like any other. */
    @Test
    void aScriptOpeningWithWithIsRecognized() {
        byte[] content = utf8("""
                WITH KOZA (KOZA_NO, ZANDAKA) AS
                  ( SELECT KOZA_NO, ZANDAKA FROM CSDB.CSQKOZA )
                SELECT KOZA_NO FROM KOZA;
                """);
        assertEquals(AssetKind.SQL, kindOf(content));
    }

    /** A byte order mark stands in front of the first keyword and must not hide it. */
    @Test
    void aByteOrderMarkDoesNotHideTheFirstStatement() {
        byte[] marked = utf8("﻿CREATE TABLE CSDB.CSQKOZA ( KOZA_NO CHAR(10) NOT NULL );\n");
        assertEquals(AssetKind.SQL, kindOf(marked));
    }

    /** A script that names another terminator ends its statements with that one, not with a ;. */
    @Test
    void aTerminatorDirectiveCountsAsEndingAStatement() {
        byte[] content = utf8("""
                --#SET TERMINATOR @
                CREATE PROCEDURE CSDB.P LANGUAGE SQL
                    BEGIN
                        UPDATE CSDB.CSQKOZA SET ZANDAKA = 0;
                    END@
                """);
        assertEquals(AssetKind.SQL, kindOf(content));
    }

    @Test
    void nulByteMarksTheFileAsBinary() {
        byte[] content = new byte[] {'I', 'D', ' ', 'D', 'I', 'V', 0, 'X'};
        SourceClassifier.Verdict verdict = SourceClassifier.classify(content);
        assertTrue(verdict.binary());
        assertNull(verdict.kind());
    }

    @Test
    void singleByteEbcdicCobolIsClassified() {
        String source = """
                      * EBCDIC COMMENT
                       IDENTIFICATION DIVISION.
                       PROGRAM-ID.  SYKEBC1.
                """;
        byte[] content = source.getBytes(Charset.forName("IBM037"));
        assertEquals(AssetKind.COBOL, kindOf(content));
    }

    @Test
    void emptyContentStaysUndecided() {
        assertNull(kindOf(new byte[0]));
    }

    /**
     * A safeguard that fails if a "first N lines only" cap creeps back in in the future. Must not
     * miss an {@code IDENTIFICATION DIVISION} that appears after a comment banner running for
     * hundreds of lines.
     */
    @Test
    void divisionAfterHundredsOfCommentLinesIsStillFound() {
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 800; i++) {
            source.append("      * 注記バナー ").append(i).append('\n');
        }
        source.append("       IDENTIFICATION DIVISION.\n")
                .append("       PROGRAM-ID.  SYKLONG.\n");
        assertEquals(AssetKind.COBOL, kindOf(utf8(source.toString())));
    }

    /** Places the same safeguard on the copybook side too. Level-number detection must not be limited to the first few lines either. */
    @Test
    void levelNumberAfterHundredsOfCommentLinesIsStillFound() {
        StringBuilder source = new StringBuilder();
        for (int i = 1; i <= 800; i++) {
            source.append("      * 注記バナー ").append(i).append('\n');
        }
        source.append("       01  SYK1-ORDER-RECORD.\n")
                .append("           05  SYK1-ORDER-NO      PIC X(10).\n");
        assertEquals(AssetKind.COPYBOOK, kindOf(utf8(source.toString())));
    }
}
