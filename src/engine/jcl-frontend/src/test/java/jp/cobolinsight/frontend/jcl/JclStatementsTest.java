package jp.cobolinsight.frontend.jcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import jp.cobolinsight.frontend.jcl.JclStatements.Kind;
import jp.cobolinsight.frontend.jcl.JclStatements.Statement;

/** The map the parser is measured against: which lines carry which statement. */
class JclStatementsTest {

    private static final Path REPO = Path.of("..", "..", "..").toAbsolutePath().normalize();

    private static List<Statement> of(String... lines) {
        return JclStatements.of(String.join("\n", lines) + "\n");
    }

    private static List<String> shape(List<Statement> statements) {
        return statements.stream()
                .map(s -> s.kind() + " " + s.name() + " " + s.line() + "-" + s.endLine()).toList();
    }

    /** A line padded out to column 72, where a non-blank character means "carried on". */
    private static String toColumn72(String line, char flag) {
        return line + " ".repeat(71 - line.length()) + flag;
    }

    @Test
    void namesEveryStatementKind() {
        List<Statement> statements = of(
                "//SYK010   JOB  (ACCT),'X'",
                "//*  a comment",
                "/*JOBPARM LINES=9999",
                "//         JCLLIB ORDER=(A.B)",
                "//         SET CYCLE=250718",
                "//         INCLUDE MEMBER=CI301",
                "//SYKPRC1  PROC A=B",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=A.B,DISP=SHR",
                "//         PEND",
                "//         IF (STEP010.RC > 4) THEN",
                "//         ELSE",
                "//         ENDIF",
                "//OUT1     OUTPUT DEST=RMT3",
                "//BADSTMT  FROBNICATE ALL",
                "//");
        assertEquals(List.of(
                "JOB SYK010 1-1", "COMMENT  2-2", "JECL  3-3", "JCLLIB  4-4", "SET  5-5",
                "INCLUDE  6-6", "PROC SYKPRC1 7-7", "EXEC STEP010 8-8", "DD IN1 9-9", "PEND  10-10",
                "IF  11-11", "ELSE  12-12", "ENDIF  13-13", "OUTPUT OUT1 14-14",
                "OTHER BADSTMT 15-15", "NULL  16-16"), shape(statements));
    }

    @Test
    void joinsContinuationLines() {
        List<Statement> statements = of(
                "//CJ203    JOB  (CJ0001),'X',CLASS=A,",
                "//             MSGLEVEL=(1,1),NOTIFY=&SYSUID",
                "//DD1      DD DSN=A.B,DISP=(NEW,CATLG,DELETE),",
                "//*           a comment written inside the statement",
                "//            UNIT=SYSDA",
                "//STEP1    EXEC PGM=IEFBR14");
        assertEquals(List.of("JOB CJ203 1-2", "DD DD1 3-5", "EXEC STEP1 6-6"), shape(statements));
    }

    /** A quoted string still open at the end of a line carries the statement onto the next. */
    @Test
    void takesAnOpenApostropheAsAContinuation() {
        List<Statement> statements = of(
                "//STEP010  EXEC PGM=SYK001,PARM='FIRST HALF",
                "//             SECOND HALF'",
                "//IN1      DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("EXEC STEP010 1-2", "DD IN1 3-3"), shape(statements));
    }

    /** Column 72 is the continuation flag, whatever the operand ends with. */
    @Test
    void takesAFlagInColumn72AsAContinuation() {
        List<Statement> statements = of(
                toColumn72("//STEP010  EXEC PGM=SYK001", 'X'),
                "//             COND=(4,LT)",
                "//IN1      DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("EXEC STEP010 1-2", "DD IN1 3-3"), shape(statements));
    }

    /**
     * A quoted string closed on the second line leaves that line with odd parity of its own. The
     * statement ends there all the same, so the SET statement under it is a statement.
     */
    @Test
    void endsTheStatementWhereTheQuotedStringCloses() {
        List<Statement> statements = of(
                "//STEP010  EXEC PGM=SYK001,PARM='FIRST HALF",
                "//             SECOND HALF'",
                "//         SET A=B",
                "//IN1      DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("EXEC STEP010 1-2", "SET  3-3", "DD IN1 4-4"), shape(statements));
    }

    /** The operand ends at the first blank, so what follows it is a comment and not an operand. */
    @Test
    void readsTheOperandFieldWithoutTheCommentAfterIt() {
        List<Statement> statements = of(
                "//DD1      DD DSN=A.B,  a comment with an apostrophe: don't",
                "//             DISP=SHR",
                "//STEP1    EXEC PGM=IEFBR14");
        assertEquals(List.of("DD DD1 1-2", "EXEC STEP1 3-3"), shape(statements));
    }

    @Test
    void readsInStreamDataUpToItsDelimiter() {
        List<Statement> statements = of(
                "//SYSIN    DD   *",
                "  DELETE SYKW.WORK",
                "  SET MAXCC=0",
                "/*",
                "//SYSUT1   DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("DD SYSIN 1-1", "DATA  2-3", "JECL  4-4", "DD SYSUT1 5-5"),
                shape(statements));
    }

    /** A DD * stream also ends where the next JCL statement begins. */
    @Test
    void endsADdAsteriskStreamAtTheNextJclStatement() {
        List<Statement> statements = of(
                "//SYSIN    DD   *",
                "  DELETE SYKW.WORK",
                "//SYSUT1   DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("DD SYSIN 1-1", "DATA  2-2", "DD SYSUT1 3-3"), shape(statements));
    }

    /** DD DATA holds JCL of its own, which only its own delimiter ends. */
    @Test
    void readsDdDataUpToTheDelimiterItNames() {
        List<Statement> statements = of(
                "//SYSUT1   DD   DATA,DLM=@@",
                "//INNER    JOB  (ACCT),'X'",
                "//INSTEP   EXEC PGM=SYK001",
                "@@",
                "//SYSUT2   DD   DSN=A.B,DISP=SHR");
        assertEquals(List.of("DD SYSUT1 1-1", "DATA  2-3", "OTHER  4-4", "DD SYSUT2 5-5"),
                shape(statements), "the delimiter line ends the data and is not part of it");
    }

    /** DATACLAS= is an ordinary parameter, not the DATA that opens an in-stream range. */
    @Test
    void doesNotTakeDataclasForDdData() {
        List<Statement> statements = of(
                "//X        DD DATACLAS=DC01,DSN=A.B,DISP=SHR",
                "//STEP2    EXEC PGM=IEFBR14",
                "//SYSOUT   DD SYSOUT=*",
                "/*");
        assertEquals(List.of("DD X 1-1", "EXEC STEP2 2-2", "DD SYSOUT 3-3", "JECL  4-4"),
                shape(statements));
    }

    /** DLM= written on a continuation line names the delimiter all the same. */
    @Test
    void readsADelimiterWrittenOnAContinuationLine() {
        List<Statement> statements = of(
                "//SYSIN    DD   DATA,",
                "//             DLM=@@",
                "//INNER    JOB  (ACCT),'X'",
                "@@",
                "//STEP2    EXEC PGM=IEFBR14");
        assertEquals(List.of("DD SYSIN 1-2", "DATA  3-3", "OTHER  4-4", "EXEC STEP2 5-5"),
                shape(statements));
    }

    /** The operation and its operand are read whatever case they were written in. */
    @Test
    void readsALowercaseDdDataStatement() {
        List<Statement> statements = of(
                "//sysin    dd   data,dlm=@@",
                "//inner    job  (acct),'x'",
                "@@",
                "//step2    exec pgm=iefbr14");
        assertEquals(List.of("DD sysin 1-1", "DATA  2-2", "OTHER  3-3", "EXEC step2 4-4"),
                shape(statements));
    }

    /** An in-stream range that meets no delimiter stops at the last line the file has. */
    @Test
    void stopsAnUnterminatedStreamAtTheLastLineOfTheFile() {
        List<Statement> statements = of(
                "//SYSIN    DD   DATA,DLM=@@",
                "  DELETE SYKW.WORK",
                "  SET MAXCC=0");
        assertEquals(List.of("DD SYSIN 1-1", "DATA  2-3"), shape(statements));
        assertEquals("@@", statements.get(0).missingDelimiter(),
                "the DD carries the delimiter it never met, so the parser can name it");
    }

    /** A DD DATA with no DLM= is still ended by its delimiter alone, and {@code /*} is that. */
    @Test
    void namesTheDefaultDelimiterOfAnUnterminatedDataStream() {
        List<Statement> statements = of(
                "//CTLCARD  DD   DATA",
                "//THIS-LOOKS-LIKE-A-DD-STATEMENT DD DSN=SAMPLE,DISP=SHR");
        assertEquals("/*", statements.get(0).missingDelimiter());
    }

    /**
     * A DD * that names a delimiter of its own is ended by that delimiter alone, the next JCL
     * statement included, so a delimiter the file never writes leaves the stream open and the
     * statements after it are swallowed by it. The DD carries the delimiter so the parser can
     * name it rather than let the rest of the file go without a word.
     */
    @Test
    void namesTheDelimiterADdAsteriskStreamNeverMet() {
        List<Statement> statements = of(
                "//SYSIN    DD   *,DLM=@@",
                "  CTL CARD",
                "//STEP020  EXEC PGM=B");
        assertEquals(List.of("DD SYSIN 1-1", "DATA  2-3"), shape(statements));
        assertEquals("@@", statements.get(0).missingDelimiter());
    }

    /** A DD * ends at the next statement, so the end of the file closes it just as well. */
    @Test
    void saysNothingAboutADdAsteriskStreamThatEndsWithTheFile() {
        assertEquals("", of("//SYSIN    DD   *", "  DELETE SYKW.WORK").get(0).missingDelimiter());
        assertEquals("", of("//SYSIN    DD   *").get(0).missingDelimiter(),
                "a DD * with no data at all is no stream left open");
    }

    /** A nameless DD under a named one is one entry of that DD's concatenation. */
    @Test
    void readsANamelessDdAsAConcatenation() {
        List<Statement> statements = of(
                "//STEPLIB  DD   DSN=A.LOADLIB,DISP=SHR",
                "//         DD   DSN=B.LOADLIB,DISP=SHR");
        assertEquals(List.of("DD STEPLIB 1-1", "DD  2-2"), shape(statements));
        assertTrue(statements.get(1).carriesModel(), "a concatenated STEPLIB is a model row");
    }

    /** JOBLIB belongs to the job, and so does the DD concatenated to it; the model keeps both. */
    @Test
    void countsJoblibAndItsConcatenationAsModelRows() {
        List<Statement> statements = of(
                "//JOBLIB   DD   DSN=A.LOADLIB,DISP=SHR",
                "//         DD   DSN=B.LOADLIB,DISP=SHR",
                "//STEP1    EXEC PGM=IEFBR14",
                "//IN1      DD   DSN=A.B,DISP=SHR");
        assertTrue(statements.get(0).carriesModel(), "JOBLIB");
        assertTrue(statements.get(1).carriesModel(), "the DD concatenated to JOBLIB");
        assertTrue(statements.get(3).carriesModel(), "an ordinary DD");
    }

    /**
     * SYSCHK is the other job-level DD. The model keeps the one checkpoint data set a restart
     * reads, so a second concatenated to it is the one DD statement nothing is measured against.
     */
    @Test
    void countsSyschkButNotADdConcatenatedToIt() {
        List<Statement> statements = of(
                "//SYSCHK   DD   DSN=A.CHKPT,DISP=OLD",
                "//         DD   DSN=B.CHKPT,DISP=OLD",
                "//STEP1    EXEC PGM=IEFBR14");
        assertTrue(statements.get(0).carriesModel(), "SYSCHK");
        assertFalse(statements.get(1).carriesModel(), "the DD concatenated to SYSCHK");
    }

    /** A field may be separated by a tab as readily as by a blank. */
    @Test
    void readsFieldsSeparatedByTabs() {
        List<Statement> statements = of(
                "//TABJOB\tJOB\t(ACCT),'X'",
                "//STEP010\tEXEC\tPGM=SYK001,",
                "//\tCOND=(4,LT)",
                "//SYSIN\tDD\tDATA,DLM=@@",
                "//INNER\tJOB\t(ACCT),'X'",
                "@@");
        assertEquals(List.of("JOB TABJOB 1-1", "EXEC STEP010 2-3", "DD SYSIN 4-4", "DATA  5-5",
                "OTHER  6-6"), shape(statements));
    }

    /** A sequence number in columns 73-80 is none of the segmenter's business. */
    @Test
    void judgesEveryFieldOnColumnsOneToSeventyOne() {
        List<Statement> statements = of(
                pad("//SEQJOB   JOB  (ACCT),'X'") + "00000010",
                pad("//STEP010  EXEC PGM=SYK001") + "00000020",
                pad("//") + "00000030");
        assertEquals(List.of("JOB SEQJOB 1-1", "EXEC STEP010 2-2", "NULL  3-3"), shape(statements));
    }

    /** A CRLF source is read exactly as an LF one is. */
    @Test
    void readsCrlfAndLfAlike() {
        String lf = String.join("\n",
                "//CRLFJOB  JOB  (ACCT),'X',CLASS=A,",
                "//             MSGLEVEL=(1,1)",
                "//STEP010  EXEC PGM=SYK001",
                "");
        assertEquals(shape(JclStatements.of(lf)),
                shape(JclStatements.of(lf.replace("\n", "\r\n"))));
    }

    @Test
    void tellsAJclStatementFromOtherText() {
        List<Statement> statements = of("PLAIN TEXT, NOT JCL");
        assertEquals(Kind.OTHER, statements.get(0).kind());
        assertFalse(statements.get(0).isJclStatement());
        assertTrue(of("//BADSTMT  FROBNICATE ALL").get(0).isJclStatement());
    }

    /** An XMIT statement transmits a payload the delimiter it names ends, JCL though it looks. */
    @Test
    void readsTheXmitPayloadOfCj503() throws IOException {
        Path file = REPO.resolve("corpus-constructs").resolve("jcl").resolve("CJ503.jcl");
        List<Statement> statements = JclStatements.of(Files.readString(file,
                StandardCharsets.UTF_8));

        assertEquals(List.of("JOB CJ503 1-2", "XMIT SYSUT2 3-3", "DATA  4-15", "OTHER  16-16"),
                shape(statements));
        assertEquals(1, statements.stream().filter(s -> s.kind() == Kind.JOB).count(),
                "the JOB card inside the payload is data, not a job of this file");
    }

    /** A JOBGROUP with no ENDGROUP costs itself; it never swallows the statements written after. */
    @Test
    void stopsAnUnclosedJobGroupAtTheFirstStatementOfTheJob() {
        List<Statement> statements = of(
                "//GRP1     JOBGROUP",
                "//J1       GJOB",
                "//J1       JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=IEFBR14",
                "//SYSPRINT DD   SYSOUT=*");
        assertEquals(List.of("GROUP GRP1 1-1", "OTHER J1 2-2", "JOB J1 3-3", "EXEC STEP010 4-4",
                "DD SYSPRINT 5-5"), shape(statements));
    }

    /** A job group is one statement from JOBGROUP to ENDGROUP, not five of them. */
    @Test
    void readsAJobGroupAsOneStatement() throws IOException {
        Path file = REPO.resolve("corpus-constructs").resolve("jcl").resolve("CJ505.jcl");
        List<Statement> statements = JclStatements.of(Files.readString(file,
                StandardCharsets.UTF_8));

        assertEquals("GROUP GRP1 1-5", shape(statements).get(0));
    }

    private static String pad(String line) {
        return line + " ".repeat(72 - line.length());
    }
}
