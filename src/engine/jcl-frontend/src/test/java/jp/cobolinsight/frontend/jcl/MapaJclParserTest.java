package jp.cobolinsight.frontend.jcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclOverrideMiss;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.JclMemberResolver;
import jp.cobolinsight.core.spi.ParseOutcome;

/**
 * The sample assertions come from samples/expected-results.md section 4. Every line number is a
 * line of the sample file as written; no preprocessed work file exists any more.
 */
class MapaJclParserTest {

    private static final Path REPO_ROOT = Paths.get("..", "..", "..").toAbsolutePath().normalize();

    private static DecodedSource decodedSource(Path file) throws IOException {
        return decodedSource(file.toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    private static DecodedSource decodedSource(String path, String text) {
        return decodedSource(path, text, StandardCharsets.UTF_8);
    }

    /** A source the caller has already decoded, which is how the pipeline hands members over. */
    private static DecodedSource decodedSource(String path, String text, Charset charset) {
        byte[] bytes = text.getBytes(charset);
        int[] offsets = new int[text.length()];
        int byteOffset = 0;
        for (int i = 0; i < text.length(); i++) {
            offsets[i] = byteOffset;
            byteOffset += String.valueOf(text.charAt(i)).getBytes(charset).length;
        }
        return new DecodedSource(path, text, bytes, offsets,
                new EncodingInfo(charset.name(), 1.0, false, false));
    }

    /** A member library the caller has decoded, keyed by member name. */
    private static JclMemberResolver library(Map<String, DecodedSource> members) {
        return name -> Optional.ofNullable(members.get(name.toUpperCase(Locale.ROOT)));
    }

    private static JclJobModel parseSample(String fileName) throws IOException {
        Path file = REPO_ROOT.resolve("samples").resolve("jcl").resolve(fileName);
        return parsed(decodedSource(file));
    }

    /** One of the constructs benchmark's files, read from disk as it stands. */
    private static Path construct(String fileName) {
        return REPO_ROOT.resolve("corpus-constructs").resolve("jcl").resolve(fileName);
    }

    private static JclStep step(JclJobModel job, String name) {
        return job.steps().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("step " + name + " not in " + job.steps()));
    }

    private static JclDdStatement dd(JclStep step, String ddName) {
        return step.ddStatements().stream()
                .filter(d -> ddName.equals(d.ddName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "DD " + ddName + " not in " + step.ddStatements()));
    }

    /** Asserts the DD's dataset name and the line it is written on. */
    private static void assertDd(JclStep step, String ddName, String dsn, int line) {
        JclDdStatement dd = dd(step, ddName);
        assertEquals(Optional.of(dsn), dd.datasetName(), ddName + " DSN");
        assertEquals(line, dd.position().line(), ddName + " line");
    }

    @Test
    void parsesSykd010() throws Exception {
        JclJobModel job = parseSample("SYKD010.jcl");
        assertEquals("SYKD010", job.jobName());
        assertEquals(2, job.steps().size());

        JclStep step010 = step(job, "STEP010");
        assertEquals(JclExecKind.PGM, step010.execKind());
        assertEquals("SYK001", step010.target());
        assertEquals(14, step010.position().line());
        assertEquals(Optional.empty(), step010.condition());
        assertDd(step010, "ORDIN", "SYKT.D250718.ORDER.DAILY", 16);
        assertDd(step010, "ORDVALID", "SYKW.D250718.ORDER.VALID", 17);
        assertDd(step010, "ORDERR", "SYKW.D250718.ORDER.ERROR", 21);
        assertEquals(Optional.empty(), dd(step010, "SYSOUT").datasetName(), "SYSOUT=* has no DSN");

        JclStep step020 = step(job, "STEP020");
        assertEquals(JclExecKind.PGM, step020.execKind());
        assertEquals("SYK002", step020.target());
        assertEquals(27, step020.position().line());
        assertEquals(Optional.of("COND=(4,LT,STEP010)"), step020.condition());
        assertDd(step020, "ORDVALID", "SYKW.D250718.ORDER.VALID", 29);
        assertDd(step020, "ORDMSTR", "SYKV.ORDER.MASTER", 30);
    }

    @Test
    void parsesSykd020WithInstreamProc() throws Exception {
        JclJobModel job = parseSample("SYKD020.jcl");
        assertEquals("SYKD020", job.jobName());
        // STEP010, the PROC call STEP020, and the step the PROC expands to.
        assertEquals(3, job.steps().size());

        JclStep step010 = step(job, "STEP010");
        assertEquals("SYK006", step010.target());
        assertEquals(22, step010.position().line());
        assertDd(step010, "STKIN", "SYKT.D250718.STOCK.DAILY", 24);
        assertDd(step010, "STKEXTR", "SYKW.D250718.STOCK.EXTRACT", 25);

        JclStep call = step(job, "STEP020");
        assertEquals(JclExecKind.PROC, call.execKind());
        assertEquals("SYKPRC01", call.target());
        assertEquals(31, call.position().line(), "the invoking EXEC's own line");

        // The expanded step keeps the line of the EXEC inside the PROC body, not the call site.
        JclStep expanded = step(job, "STEP020.STEP020");
        assertEquals(JclExecKind.PGM, expanded.execKind());
        assertEquals("SYK007", expanded.target());
        assertEquals(16, expanded.position().line());
        // CYCLE=&CYCLE on the EXEC beats the PROC's own default of 000000.
        assertDd(expanded, "STKEXTR", "SYKW.D250718.STOCK.EXTRACT", 18);
    }

    @Test
    void parsesSykd030() throws Exception {
        JclJobModel job = parseSample("SYKD030.jcl");
        assertEquals("SYKD030", job.jobName());
        assertEquals(2, job.steps().size());

        JclStep step010 = step(job, "STEP010");
        assertEquals("SYK001", step010.target());
        assertEquals(13, step010.position().line());
        assertDd(step010, "ORDIN", "SYKW.D250718.ORDER.ERROR", 15);
        assertDd(step010, "ORDVALID", "SYKW.D250718.ORDER.RERUN.VALID", 16);
        assertDd(step010, "ORDERR", "SYKW.D250718.ORDER.RERUN.ERROR", 20);

        JclStep step020 = step(job, "STEP020");
        assertEquals("SYK002", step020.target());
        assertEquals(26, step020.position().line());
        assertEquals(Optional.of("COND=(4,LT,STEP010)"), step020.condition());
        assertDd(step020, "ORDVALID", "SYKW.D250718.ORDER.RERUN.VALID", 28);
        assertDd(step020, "ORDMSTR", "SYKV.ORDER.MASTER", 29);
    }

    @Test
    void substitutesSymbolicsAndLeavesUnknownOnesAlone() {
        Map<String, String> scope = Map.of("CYCLE", "250718", "HLQ", "SYKW");
        assertEquals("SYKW.D250718.ORDER.VALID",
                MapaJclParser.resolveSymbols("&HLQ..D&CYCLE..ORDER.VALID", scope));
        assertEquals("250718", MapaJclParser.resolveSymbols("&CYCLE", scope));
        assertEquals("SYK.&UNKNOWN..DATA",
                MapaJclParser.resolveSymbols("SYK.&UNKNOWN..DATA", scope));
        assertEquals("&&SYSUID.DATA", MapaJclParser.resolveSymbols("&&SYSUID.DATA", scope),
                "a doubled ampersand is a deferred system symbol");
    }

    @Test
    void readsCrlfAndLfAlike() {
        String lf = String.join("\n",
                "//CRLFJOB  JOB  (ACCT),'X',CLASS=A",
                "//         SET CYCLE=250718",
                "//STEP010  EXEC PGM=SYK001,",
                "//             COND=(4,LT)",
                "//ORDIN    DD   DSN=SYKT.D&CYCLE..ORDER.DAILY,DISP=SHR",
                "");
        JclJobModel fromLf = parsed(decodedSource("lf.jcl", lf));
        JclJobModel fromCrlf = parsed(decodedSource("crlf.jcl", lf.replace("\n", "\r\n")));

        assertEquals("SYKT.D250718.ORDER.DAILY",
                dd(fromLf.steps().get(0), "ORDIN").datasetName().orElseThrow());
        assertEquals(fromLf.steps().size(), fromCrlf.steps().size());
        assertEquals(dd(fromLf.steps().get(0), "ORDIN").datasetName(),
                dd(fromCrlf.steps().get(0), "ORDIN").datasetName());
        assertEquals(3, fromCrlf.steps().get(0).position().line());
        assertEquals(5, dd(fromCrlf.steps().get(0), "ORDIN").position().line(),
                "the continued EXEC statement must not shift the DD's line");
    }

    @Test
    void expandsCataloguedProcThroughTheResolver() {
        DecodedSource member = decodedSource("proclib/SYKPRC99.proc", String.join("\n",
                "//SYKPRC99 PROC CYCLE=000000",
                "//PRCSTEP  EXEC PGM=SYK099",
                "//PRCIN    DD   DSN=SYKW.D&CYCLE..STOCK.EXTRACT,DISP=SHR",
                "//         PEND",
                ""));
        String job = String.join("\n",
                "//CATJOB   JOB  (ACCT),'X',CLASS=A",
                "//         SET CYCLE=250718",
                "//STEP010  EXEC SYKPRC99,CYCLE=&CYCLE",
                "");

        JclJobModel model = parsed(decodedSource("catjob.jcl", job),
                library(Map.of("SYKPRC99", member)));

        assertEquals(2, model.steps().size());
        JclStep expanded = step(model, "STEP010.PRCSTEP");
        assertEquals("SYK099", expanded.target());
        // A catalogued PROC's steps carry the line and the file of the PROC member.
        assertEquals(2, expanded.position().line());
        assertEquals("proclib/SYKPRC99.proc", expanded.position().file());
        assertDd(expanded, "PRCIN", "SYKW.D250718.STOCK.EXTRACT", 3);
        assertEquals(List.of("proclib/SYKPRC99.proc"), model.members());
    }

    /** The caller decodes; a member written in Shift_JIS is no different from any other here. */
    @Test
    void expandsAShiftJisMemberTheCallerDecoded() {
        DecodedSource member = decodedSource("proclib/SYKPRC97.proc", String.join("\n",
                "//SYKPRC97 PROC HLQ=SYKW",
                "//*  在庫抽出のひな型です。",
                "//PRCSTEP  EXEC PGM=SYK097",
                "//PRCOUT   DD   DSN=&HLQ..STOCK.EXTRACT,DISP=(NEW,CATLG)",
                "//         PEND",
                ""), Charset.forName("windows-31j"));
        String job = String.join("\n",
                "//SJISJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC SYKPRC97,HLQ=SYKV",
                "");

        JclJobModel model = parsed(decodedSource("sjisjob.jcl", job),
                library(Map.of("SYKPRC97", member)));

        assertDd(step(model, "STEP010.PRCSTEP"), "PRCOUT", "SYKV.STOCK.EXTRACT", 4);
    }

    @Test
    void stepsInsideIfCarryTheIfAsTheirCondition() {
        String job = String.join("\n",
                "//IFJOB    JOB  (ACCT),'X',CLASS=A",
                "//STEP010  EXEC PGM=SYK001",
                "//         IF (STEP010.RC > 4) THEN",
                "//STEP020  EXEC PGM=SYK002",
                "//         ELSE",
                "//STEP030  EXEC PGM=SYK003,COND=(0,NE)",
                "//         ENDIF",
                "//STEP040  EXEC PGM=SYK004",
                "");
        JclJobModel model = parsed(decodedSource("ifjob.jcl", job));
        assertEquals(Optional.empty(), step(model, "STEP010").condition());
        assertTrue(step(model, "STEP020").condition().orElseThrow().startsWith("IF"),
                () -> step(model, "STEP020").condition().toString());
        assertEquals(Optional.of("COND=(0,NE) IF (STEP010.RC > 4) ELSE"),
                step(model, "STEP030").condition(),
                "a COND of its own and the enclosing IF both guard the step, the COND first");
        assertEquals(Optional.empty(), step(model, "STEP040").condition(),
                "after ENDIF the guard is gone");
    }

    @Test
    void keepsTheDispositionOfEveryDd() {
        String job = String.join("\n",
                "//DISPJOB  JOB  (ACCT),'X',CLASS=A",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//OUT1     DD   DSN=SYKW.OUT,DISP=(NEW,CATLG,DELETE),",
                "//             SPACE=(CYL,(1,1))",
                "//SYSOUT   DD   SYSOUT=*",
                "");
        JclStep step = parsed(decodedSource("dispjob.jcl", job)).steps().get(0);
        assertEquals(Optional.of("SHR"), dd(step, "IN1").dispositionText());
        assertEquals(Optional.of("(NEW,CATLG,DELETE)"), dd(step, "OUT1").dispositionText());
        assertEquals(Optional.empty(), dd(step, "SYSOUT").dispositionText());
    }

    @Test
    void recognisesAProcedureLibraryMember() {
        String member = String.join("\n",
                "//SYKPRC98 PROC CYCLE=000000",
                "//PRCSTEP  EXEC PGM=SYK098",
                "//         PEND",
                "");
        MapaJclParser parser = new MapaJclParser();
        assertTrue(parser.isMember(decodedSource("SYKPRC98.proc", member)));
        assertFalse(parser.isMember(decodedSource("job.jcl",
                "//JOB1 JOB (ACCT),'X'\n//STEP010 EXEC PGM=SYK001\n")));
        assertFalse(parser.isMember(decodedSource("not-jcl.txt", "PLAIN TEXT\n")));
    }

    /** An INCLUDE member of SET statements alone carries no PROC and is a member all the same. */
    @Test
    void recognisesAnIncludeMemberWithoutAProc() {
        assertTrue(new MapaJclParser().isMember(decodedSource("SYKINC01.inc", String.join("\n",
                "//*  the run's symbols",
                "//         SET ENV=PROD,RUNID=SYK308",
                ""))));
    }

    /** A member with a broken statement in it is still a member, not a job that failed. */
    @Test
    void recognisesABrokenProcedureLibraryMember() {
        String member = String.join("\n",
                "//SYKPRC96 PROC CYCLE=000000",
                "//PRCSTEP  EXEC PGM=SYK096",
                "//BADDD    DD   DSN=(((,DISP=SHR",
                "//         PEND",
                "");
        assertTrue(new MapaJclParser().isMember(decodedSource("SYKPRC96.proc", member)));
    }

    @Test
    void reportsAMissingJobCardAsAnErrorFinding() {
        ParseOutcome<List<JclJobModel>> outcome = new MapaJclParser()
                .parse(decodedSource("not-jcl.txt", "PLAIN TEXT, NOT JCL\n"),
                        JclMemberResolver.NONE);

        assertFalse(outcome.isSuccess());
        Finding finding = outcome.failureFinding().orElseThrow();
        assertEquals(Finding.PARSE_FAILURE_RULE_ID, finding.ruleId());
        assertTrue(finding.message().contains("JOB 文が見つかりません"), finding.message());
    }

    /** A statement the grammar cannot read costs that statement, not the steps around it. */
    @Test
    void recoversFromABrokenStatementAndReportsItsLine() {
        String job = String.join("\n",
                "//RECJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//BADSTMT  FROBNICATE ALL",
                "//STEP020  EXEC PGM=SYK002",
                "//IN2      DD   DSN=SYKT.IN2,DISP=SHR",
                "");
        JclJobModel model = parsed(decodedSource("recjob.jcl", job));

        assertEquals(List.of("STEP010", "STEP020"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
        assertDd(step(model, "STEP020"), "IN2", "SYKT.IN2", 6);
        assertEquals(List.of(4), syntaxLines(model));
        assertEquals(FindingLevel.WARNING, syntax(model).get(0).level());
        assertTrue(syntax(model).get(0).message().startsWith("4 行の JCL 文を解析できませんでした（"),
                syntax(model).get(0).message());
    }

    /**
     * The statements around an unreadable one survive even where recovery inside the whole-file
     * rule would give up on them: an EXEC with no program name is read as one statement failing,
     * not as the end of the job.
     */
    @Test
    void keepsTheStatementsAroundAnUnreadableExec() {
        String job = String.join("\n",
                "//RECJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//BAD      EXEC PGM=",
                "//STEP020  EXEC PGM=SYK002",
                "");
        JclJobModel model = parsed(decodedSource("badexec.jcl", job));

        assertEquals(List.of("STEP010", "STEP020"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
        assertEquals(List.of(4), syntaxLines(model));
    }

    /**
     * corpus-constructs/jcl/CJ203.jcl as it stands on disk: two steps of continued DD statements,
     * one of them written with a DCB referback the grammar does not accept. Reading the file whole
     * loses every statement around it, and reading each statement on its own used to lose the
     * statement itself; with the parameter put aside the DD keeps its dataset name.
     */
    @Test
    void keepsEveryReadableStatementOfAFileFullOfContinuations() throws Exception {
        JclJobModel model = parsed(decodedSource(construct("CJ203.jcl")));

        assertEquals(List.of("STEP1", "STEP2"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "STEP1"), "DD1", "FLW.CJ203.BASE", 11);
        assertDd(step(model, "STEP2"), "DD2", "FLW.CJ203.REFBACK", 15);
        assertDd(step(model, "STEP2"), "DD3", "FLW.CJ203.REFBACK2", 18);
        assertDd(step(model, "STEP2"), "MODELDS", "FLW.CJ203.MODELED", 22);
        assertDd(step(model, "STEP2"), "OUTSIDE", "FLW.CJ203.OUTSIDE", 26);
        // DCB=MODEL.DSCB on MODELDS is the one parameter the grammar refuses, and the only thing
        // reported; the DD around it stands.
        assertEquals(List.of(22), syntaxLines(model));
        assertTrue(syntax(model).get(0).message().contains("パラメーター DCB"),
                syntax(model).get(0).message());
    }

    /**
     * A nameless DD written under a DD the grammar refused is concatenated to nothing: the DD
     * above it produced no row, so filing it under the DD before that one would say the job
     * concatenated two data sets it never wrote together.
     */
    @Test
    void concatenatesNothingToADdTheGrammarRefused() {
        String job = String.join("\n",
                "//BADJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//DD1      DD   DSN=A.A,DISP=SHR",
                "//DD2      DD   DSN=(((,DISP=SHR",
                "//         DD   DSN=C.C,DISP=SHR",
                "");
        JclJobModel model = parsed(decodedSource("concat.jcl", job));

        assertEquals(List.of("DD1"), step(model, "STEP010").ddStatements().stream()
                .map(JclDdStatement::ddName).toList());
        assertEquals(List.of(4, 5), syntaxLines(model));
    }

    /** A recovered value carries the tokens the parser invented; none of them reaches the model. */
    @Test
    void neverTakesAValueRecoveryHadAHandIn() {
        String job = String.join("\n",
                "//BADJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//BADDD    DD   DSN=(((,DISP=SHR",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "");
        JclJobModel model = parsed(decodedSource("baddd.jcl", job));

        JclStep step = step(model, "STEP010");
        assertEquals(List.of("IN1"), step.ddStatements().stream()
                .map(JclDdStatement::ddName).toList());
        assertEquals(List.of(3), syntaxLines(model));
    }

    /**
     * A statement the whole-file rule swallows is noticed by the cross-check — the statement map
     * says it was there and the model holds no row for it — and the file is read again statement
     * by statement, which gets it back. An unknown keyword on an EXEC is such a statement.
     */
    @Test
    void takesTheFallbackWhenTheWholeFileParseSwallowsAStatement() {
        String job = String.join("\n",
                "//SWJOB    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001,FROBNICATE=YES",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//STEP020  EXEC PGM=SYK002",
                "");
        MapaJclParser parser = new MapaJclParser();
        ParseOutcome<List<JclJobModel>> outcome =
                parser.parse(decodedSource("swallow.jcl", job), JclMemberResolver.NONE);
        JclJobModel model = outcome.value().orElseThrow().get(0);

        assertTrue(parser.segmented(), "the cross-check must have sent the file round again");
        assertEquals(List.of("STEP010", "STEP020"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
        assertEquals(List.of(), syntaxLines(model),
                "the second reading lost nothing, so nothing is reported");
    }

    /**
     * A DD written inside a CNTL block is dropped by the whole-file rule without a word from the
     * parser: only the statement map knows it was there. The second reading models it.
     */
    @Test
    void getsBackADdTheWholeFileRuleDropsWithoutAnError() {
        String job = String.join("\n",
                "//CNTLJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//MYCNTL   CNTL",
                "//DDIN     DD   DSN=SYKT.IN,DISP=SHR",
                "//         ENDCNTL",
                "");
        MapaJclParser parser = new MapaJclParser();
        ParseOutcome<List<JclJobModel>> outcome =
                parser.parse(decodedSource("cntl.jcl", job), JclMemberResolver.NONE);
        JclJobModel model = outcome.value().orElseThrow().get(0);

        assertTrue(parser.segmented(), "the cross-check must have sent the file round again");
        assertDd(step(model, "STEP010"), "DDIN", "SYKT.IN", 4);
        assertEquals(List.of(), syntaxLines(model));
    }

    /**
     * A CNTL card the grammar refuses is reported. The two cards of the group carry no model row,
     * which is why neither used to be read at all; a card nobody could read is worth a word all
     * the same, the way a JECL or an XMIT card is.
     */
    @Test
    void reportsACntlCardTheGrammarRefuses() {
        String job = String.join("\n",
                "//CNTLJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//MYCNTL   CNTL ((((",
                "//DDIN     DD   DSN=SYKT.IN,DISP=SHR",
                "//         ENDCNTL",
                "");
        JclJobModel model = parsed(decodedSource("badcntl.jcl", job));

        assertDd(step(model, "STEP010"), "DDIN", "SYKT.IN", 4);
        assertEquals(List.of(3), syntaxLines(model));
    }

    /** JES2 cards written before the JOB card cost themselves, not the job. */
    @Test
    void keepsTheJobWhenJes2CardsPrecedeIt() {
        String job = String.join("\n",
                "/*SIGNON       REMOTE3",
                "/*PRIORITY 8",
                "//CJ501    JOB  (CJ0001),'JES2 JECL',CLASS=A",
                "//STEP010  EXEC PGM=IEFBR14",
                "//SYSPRINT DD   SYSOUT=*",
                "");
        JclJobModel model = parsed(decodedSource("jes2.jcl", job));

        assertEquals("CJ501", model.jobName());
        assertEquals(List.of("STEP010"), model.steps().stream().map(JclStep::name).toList());
        assertTrue(syntaxLines(model).contains(1),
                () -> "the card the grammar refuses is reported: " + syntaxLines(model));
    }

    /** A syntax error inside a member is reported at the line of the member's own file. */
    @Test
    void reportsAMemberSyntaxErrorAgainstTheMember() {
        DecodedSource member = decodedSource("proclib/SYKPRC95.proc", String.join("\n",
                "//SYKPRC95 PROC",
                "//PRCSTEP  EXEC PGM=SYK095",
                "//BADSTMT  FROBNICATE ALL",
                "//         PEND",
                ""));
        String job = String.join("\n",
                "//MEMJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC SYKPRC95",
                "");

        JclJobModel model = parsed(decodedSource("memjob.jcl", job),
                library(Map.of("SYKPRC95", member)));

        Finding syntax = syntax(model).get(0);
        assertEquals("proclib/SYKPRC95.proc", syntax.location().file());
        assertEquals(3, syntax.location().line());
        assertEquals("SYK095", step(model, "STEP010.PRCSTEP").target(),
                "the member's other steps are expanded all the same");
    }

    /** An INCLUDE member of DD statements alone: no rule of the grammar reads it on its own. */
    @Test
    void addsTheDdStatementsOfAnIncludeMemberToTheStep() {
        DecodedSource member = decodedSource("proclib/CI301.inc", String.join("\n",
                "//*  the report DD every step of this job shares",
                "//SYSPRINT DD   SYSOUT=*",
                "//RPTDD    DD   DSN=SYKW.REPORT,DISP=SHR",
                ""));
        String job = String.join("\n",
                "//INCJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//         INCLUDE MEMBER=CI301",
                "");

        assertTrue(new MapaJclParser().isMember(member), "a DD-only member is a member");
        JclJobModel model = parsed(decodedSource("incjob.jcl", job),
                library(Map.of("CI301", member)));

        assertEquals(List.of("IN1", "SYSPRINT", "RPTDD"), step(model, "STEP010").ddStatements()
                .stream().map(JclDdStatement::ddName).toList());
        assertEquals(List.of("proclib/CI301.inc"), model.members());
        assertEquals(List.of(), syntaxLines(model));
    }

    /** A member that INCLUDEs itself is expanded once and reported, not eight times over. */
    @Test
    void stopsAMemberThatIncludesItself() {
        DecodedSource member = decodedSource("proclib/SYKINC9.inc", String.join("\n",
                "//STEPM    EXEC PGM=SYK099",
                "//         INCLUDE MEMBER=SYKINC9",
                ""));
        String job = String.join("\n",
                "//LOOPJOB  JOB  (ACCT),'X'",
                "//         INCLUDE MEMBER=SYKINC9",
                "");

        JclJobModel model = parsed(decodedSource("loopjob.jcl", job),
                library(Map.of("SYKINC9", member)));

        assertEquals(List.of("STEPM"), model.steps().stream().map(JclStep::name).toList());
        assertTrue(syntax(model).stream().anyMatch(f -> f.message().contains("SYKINC9")),
                () -> model.diagnostics().toString());
    }

    @Test
    void keepsEveryJobOfAFile() {
        String file = String.join("\n",
                "//JOBA     JOB  (ACCT),'A'",
                "//         SET CYCLE=250718",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.D&CYCLE..IN,DISP=SHR",
                "//JOBB     JOB  (ACCT),'B'",
                "//STEP020  EXEC PGM=SYK002",
                "");
        List<JclJobModel> jobs = parsedJobs(decodedSource("twojobs.jcl", file));

        assertEquals(List.of("JOBA", "JOBB"), jobs.stream().map(JclJobModel::jobName).toList());
        assertEquals(List.of("STEP010"), jobs.get(0).steps().stream().map(JclStep::name).toList());
        assertEquals(List.of("STEP020"), jobs.get(1).steps().stream().map(JclStep::name).toList());
        assertEquals("SYKT.D250718.IN",
                dd(jobs.get(0).steps().get(0), "IN1").datasetName().orElseThrow());
    }

    @Test
    void recordsTheJcllibOrderLibraries() {
        String job = String.join("\n",
                "//LIBJOB   JOB  (ACCT),'X'",
                "//         JCLLIB ORDER=(FL.PROD.PROCLIB,FL.TEST.PROCLIB)",
                "//STEP010  EXEC PGM=SYK001",
                "");
        assertEquals(List.of("FL.PROD.PROCLIB", "FL.TEST.PROCLIB"),
                parsed(decodedSource("libjob.jcl", job)).jcllib());
    }

    /** A JCLLIB ORDER belongs to the job it stands in; the next job of the file orders its own. */
    @Test
    void givesEachJobOfAFileItsOwnJcllibOrder() {
        String file = String.join("\n",
                "//JOBA     JOB  (ACCT),'A'",
                "//         JCLLIB ORDER=(FL.PROD.PROCLIB)",
                "//STEP010  EXEC PGM=SYK001",
                "//JOBB     JOB  (ACCT),'B'",
                "//         JCLLIB ORDER=(FL.TEST.PROCLIB)",
                "//STEP010  EXEC PGM=SYK002",
                "");
        List<JclJobModel> jobs = parsedJobs(decodedSource("twojobs.jcl", file));

        assertEquals(List.of("FL.PROD.PROCLIB"), jobs.get(0).jcllib());
        assertEquals(List.of("FL.TEST.PROCLIB"), jobs.get(1).jcllib());
    }

    /** Control-M and CA7 write into JCL the grammar would refuse; both survive the parse. */
    @Test
    void keepsSchedulerMarkersAndTheDatasetsAroundThem() {
        String job = String.join("\n",
                "//CTMJOB   JOB  (ACCT),'X'",
                "#JI JOBNAME=CTMJOB",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR",
                "//OUT1     DD   DSN=SYKW.$ODATE.ORDER,DISP=(NEW,CATLG)",
                "//*%OPC SCAN",
                "");
        JclJobModel model = parsed(decodedSource("ctmjob.jcl", job));

        JclStep step = step(model, "STEP010");
        assertEquals(Optional.of("SYKT.%%ODATE.ORDER"), dd(step, "IN1").datasetName());
        assertEquals(Optional.of("SYKW.$ODATE.ORDER"), dd(step, "OUT1").datasetName());
        assertEquals(List.of("%%ODATE", "$ODATE"), model.schedulerVariables());
        List<Finding> directives = model.diagnostics().stream()
                .filter(f -> Finding.JCL_DIRECTIVE_RULE_ID.equals(f.ruleId())).toList();
        assertEquals(List.of(2, 6), directives.stream().map(f -> f.location().line()).toList());
        assertEquals(FindingLevel.NOTE, directives.get(0).level());
        assertTrue(directives.get(0).message().contains("#JI JOBNAME=CTMJOB"),
                directives.get(0).message());
        assertEquals(List.of(), syntaxLines(model),
                "the marker lines must not read as broken statements");
    }

    /**
     * A symbol of the source may be called C00001; the placeholder must not answer to it. The
     * serial that skips a clash belongs to the parse, so the token after it does not take the
     * name the clashing one skipped.
     */
    @Test
    void schedulerPlaceholdersDoNotClashWithTheSourcesOwnSymbols() {
        String job = String.join("\n",
                "//CLASHJOB JOB  (ACCT),'X'",
                "//         SET C00001=CLASH",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR",
                "//OUT1     DD   DSN=SYKW.%%OJOBID.ORDER,DISP=(NEW,CATLG)",
                "");
        JclJobModel model = parsed(decodedSource("clash.jcl", job));

        assertEquals(Optional.of("SYKT.%%ODATE.ORDER"),
                dd(step(model, "STEP010"), "IN1").datasetName());
        assertEquals(Optional.of("SYKW.%%OJOBID.ORDER"),
                dd(step(model, "STEP010"), "OUT1").datasetName(),
                "the second token keeps a name of its own");
        assertEquals(List.of("%%ODATE", "%%OJOBID"), model.schedulerVariables());
    }

    /**
     * The fast path and the fallback must model the same thing. Every JCL of the five asset
     * folders is read both ways and the two models are compared, so a file the fast path reads
     * whole cannot be modelled one way there and another way when some later change sends it down
     * the fallback. Only the files the fast path really read are counted: comparing the fallback
     * with itself would prove nothing, and {@link MapaJclParser#segmented()} says which happened.
     */
    @Test
    void bothPathsBuildTheSameModel() throws IOException {
        int compared = 0;
        int fellBack = 0;
        for (Path directory : List.of(REPO_ROOT.resolve("samples"),
                REPO_ROOT.resolve("samples-field"), REPO_ROOT.resolve("corpus"),
                REPO_ROOT.resolve("corpus-constructs"), REPO_ROOT.resolve("corpus-public"))) {
            Map<Path, JclMemberResolver> libraries = new java.util.HashMap<>();
            for (Path file : jclFilesUnder(directory)) {
                DecodedSource source = decodedSource(file);
                JclMemberResolver members = libraries.computeIfAbsent(file.getParent(),
                        MapaJclParserTest::directoryLibrary);
                MapaJclParser parser = new MapaJclParser();
                ParseOutcome<List<JclJobModel>> whole = parser.parse(source, members, false);
                if (parser.segmented()) {
                    // The fast path did not run, so the fallback has nothing to be compared with.
                    fellBack++;
                    continue;
                }
                ParseOutcome<List<JclJobModel>> segmented = parser.parse(source, members, true);
                assertEquals(whole.isSuccess(), segmented.isSuccess(), file.toString());
                if (whole.isSuccess()) {
                    assertEquals(withoutDiagnostics(whole.value().orElseThrow()),
                            withoutDiagnostics(segmented.value().orElseThrow()), file.toString());
                }
                compared++;
            }
        }
        assertTrue(compared >= 30, "the fast path ran for " + compared + " files and the "
                + "fallback for " + fellBack + "; too few were compared as the fast path read them");
    }

    /**
     * The DD statements under an EXEC the grammar refuses belong to no step, not to the one
     * before, and each of them is reported: the row is lost, so the reader is told which.
     */
    @Test
    void reportsTheDdStatementsOfAnUnreadableExecRatherThanFilingThemNowhere() {
        String job = String.join("\n",
                "//RECJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "//BAD      EXEC PGM=",
                "//IN2      DD   DSN=SYKT.IN2,DISP=SHR",
                "//STEP020  EXEC PGM=SYK002",
                "");
        JclJobModel model = parsed(decodedSource("badexec.jcl", job));

        assertEquals(List.of("IN1"), step(model, "STEP010").ddStatements().stream()
                .map(JclDdStatement::ddName).toList());
        assertEquals(List.of(), step(model, "STEP020").ddStatements());
        assertEquals(List.of(4, 5), syntaxLines(model));
        assertEquals("5 行の DD 文は、EXEC 文を解析できないため、"
                + "どのステップにも取り込めませんでした。",
                syntax(model).get(1).message());
    }

    /** A DD concatenated to JOBLIB belongs to the job, which the model keeps no row for. */
    @Test
    void saysNothingAboutADdConcatenatedToJoblib() {
        String job = String.join("\n",
                "//LIBJOB   JOB  (ACCT),'X'",
                "//JOBLIB   DD   DSN=A.LOADLIB,DISP=SHR",
                "//         DD   DSN=B.LOADLIB,DISP=SHR",
                "//STEP010  EXEC PGM=SYK001",
                "");
        JclJobModel model = parsed(decodedSource("joblib.jcl", job));

        assertEquals(List.of("STEP010"), model.steps().stream().map(JclStep::name).toList());
        assertEquals(List.of(), step(model, "STEP010").ddStatements(),
                "a job-level DD is no step's own");
        assertEquals(List.of(), syntaxLines(model));
    }

    /**
     * An XMIT payload is data, JCL though it reads: it runs on another node. Neither the JOB card
     * inside it nor its steps belong to the job that transmits it.
     */
    @Test
    void readsTheXmitPayloadOfCj503AsData() throws Exception {
        List<JclJobModel> jobs = parsedJobs(decodedSource(construct("CJ503.jcl")));

        assertEquals(List.of("CJ503"), jobs.stream().map(JclJobModel::jobName).toList());
        assertEquals(List.of(), jobs.get(0).steps(),
                "the payload's own steps run where it is sent, not here");
        assertEquals(List.of(), syntaxLines(jobs.get(0)));
    }

    /**
     * The IMS batch region's positional PARM is everyday JCL the grammar refuses. The parameter is
     * put aside and reported; the step it stands on keeps its program.
     */
    @Test
    void keepsTheStepOfAnImsBatchRegionParm() throws Exception {
        JclJobModel model = parsed(decodedSource(construct("CJ422.jcl")));

        assertEquals("DFSRRC00", step(model, "STEP010").target());
        assertEquals(List.of("STEPLIB", "STEPLIB", "IEFRDER", "DFSRESLB", "DFSVSAMP", "IMS", "IMS",
                "SYSPRINT", "SYSUDUMP"), step(model, "STEP010").ddStatements().stream()
                        .map(JclDdStatement::ddName).toList());
        assertEquals(List.of(9), syntaxLines(model));
        assertEquals("9 行の JCL 文のパラメーター PARM を解析できませんでした。"
                + "値は原文のまま保持します。", syntax(model).get(0).message());
        assertEquals(FindingLevel.WARNING, syntax(model).get(0).level());
    }

    /** A DCB that names a model DSCB is put aside, and the DD keeps its own dataset name. */
    @Test
    void keepsTheDatasetNameOfADdWhoseDcbIsRefused() {
        String job = String.join("\n",
                "//DCBJOB    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//MODELDS  DD   DSN=SYKT.OUT,DISP=(NEW,CATLG),DCB=MODEL.DSCB",
                "");
        JclJobModel model = parsed(decodedSource("dcb.jcl", job));

        assertDd(step(model, "STEP010"), "MODELDS", "SYKT.OUT", 3);
        assertTrue(syntax(model).get(0).message().contains("パラメーター DCB"),
                syntax(model).get(0).message());
    }

    /**
     * The stand-in for a refused value never reaches the model, however short the value is: the
     * step keeps the program it names and the DD keeps its disposition.
     */
    @Test
    void putsBackAValueTheModelKeeps() {
        String job = String.join("\n",
                "//STANDJOB JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=IEFBR14,PARM=(A,B,C,,,)",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR,DCB=MODEL.DSCB",
                "");
        JclJobModel model = parsed(decodedSource("standin.jcl", job));

        assertEquals("IEFBR14", step(model, "STEP010").target(),
                "the program is what the step runs, not a stand-in for it");
        assertEquals(Optional.of("SHR"), dd(step(model, "STEP010"), "IN1").dispositionText());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
    }

    /**
     * A value too short to stand in for — one character — leaves the parameter to be taken out
     * rather than stood in for, and the message says which of the two happened.
     */
    @Test
    void saysWhichParameterItTookOutOfTheStatement() {
        String job = String.join("\n",
                "//DROPJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001,PARM=(",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "");
        JclJobModel model = parsed(decodedSource("drop.jcl", job));

        assertEquals("SYK001", step(model, "STEP010").target());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
        assertEquals("2 行の JCL 文のパラメーター PARM を解析できないため、取り除いて解析しました。",
                syntax(model).get(0).message());
    }

    /** A message quotes what the parser read, so what it quotes must be what the author wrote. */
    @Test
    void quotesTheAuthorsOwnTextInADiagnostic() {
        String job = String.join("\n",
                "//TOKENJOB JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//BADSTMT  FROBNICATE DSN=SYKT.%%ODATE.ORDER",
                "");
        JclJobModel model = parsed(decodedSource("token.jcl", job));

        assertEquals(List.of(3), syntaxLines(model));
        assertFalse(syntax(model).get(0).message().contains("&C"),
                () -> "no placeholder may reach the reader: " + syntax(model).get(0).message());
    }

    /** A scheduler token inside a value the grammar refuses is still put back into the model. */
    @Test
    void putsBackASchedulerTokenInsideARefusedParameter() {
        String job = String.join("\n",
                "//NESTJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR,DCB=MODEL.%%ODATE",
                "");
        JclJobModel model = parsed(decodedSource("nest.jcl", job));

        assertEquals(Optional.of("SYKT.%%ODATE.ORDER"),
                dd(step(model, "STEP010"), "IN1").datasetName());
        assertEquals(List.of("%%ODATE"), model.schedulerVariables());
    }

    /** A stream only its delimiter can end says which delimiter it was still looking for. */
    @Test
    void namesTheDelimiterAnInStreamRangeNeverMet() {
        String named = String.join("\n",
                "//DLMJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//SYSIN    DD   DATA,DLM=@@",
                "  DELETE SYKW.WORK",
                "");
        assertEquals("3 行の DLM に指定した区切り文字 @@ が現れないまま、"
                + "ファイルの終わりに達しました。",
                syntax(parsed(decodedSource("dlm.jcl", named))).get(0).message());

        String plain = String.join("\n",
                "//DLMJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//CTLCARD  DD   DATA",
                "  DELETE SYKW.WORK",
                "");
        assertEquals("3 行のインストリームデータに区切り行 /* がないまま、"
                + "ファイルの終わりに達しました。",
                syntax(parsed(decodedSource("plain.jcl", plain))).get(0).message());
    }

    /** An unreadable PROC statement closes the PROC before it as a readable one would. */
    @Test
    void closesTheOpenProcOfAnUnreadableProcStatement() {
        String file = String.join("\n",
                "//PRCJOB   JOB  (ACCT),'X'",
                "//PROC1    PROC",
                "//PSTEP1   EXEC PGM=SYK001",
                "//PROC2    PROC (((",
                "//PSTEP2   EXEC PGM=SYK002",
                "//         PEND",
                "//STEP010  EXEC PROC1",
                "");
        JclJobModel model = parsed(decodedSource("procs.jcl", file));

        assertFalse(model.steps().stream().map(JclStep::name).anyMatch("STEP010.PSTEP2"::equals),
                () -> "PROC1 was closed where PROC2 began, so PSTEP2 is none of its steps: "
                        + model.steps().stream().map(JclStep::name).toList());
        assertEquals("SYK001", step(model, "STEP010.PSTEP1").target());
    }

    /** A keyword no rule of the grammar knows costs that keyword, not the step it stands on. */
    @Test
    void keepsTheStepOfAnUnknownKeyword() {
        String job = String.join("\n",
                "//FROBJOB  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001,FROBNICATE=YES",
                "//IN1      DD   DSN=SYKT.IN,DISP=SHR",
                "");
        JclJobModel model = parsed(decodedSource("frob.jcl", job));

        assertEquals("SYK001", step(model, "STEP010").target());
        assertDd(step(model, "STEP010"), "IN1", "SYKT.IN", 3);
    }

    /** A PROC the author never PENDed would swallow the next job; the JOB card closes it. */
    @Test
    void closesAProcWithoutItsPendAtTheNextJobCard() {
        String file = String.join("\n",
                "//JOBA     JOB  (ACCT),'A'",
                "//MYPROC   PROC",
                "//PSTEP    EXEC PGM=SYK099",
                "//JOBB     JOB  (ACCT),'B'",
                "//STEP020  EXEC PGM=SYK002",
                "");
        List<JclJobModel> jobs = parsedJobs(decodedSource("unpended.jcl", file));

        assertEquals(List.of("JOBA", "JOBB"), jobs.stream().map(JclJobModel::jobName).toList());
        assertEquals(List.of("STEP020"), jobs.get(1).steps().stream().map(JclStep::name).toList());
        assertEquals(List.of(2), syntaxLines(jobs.get(0)));
        assertTrue(syntax(jobs.get(0)).get(0).message().contains("PEND"),
                syntax(jobs.get(0)).get(0).message());
    }

    /** Past fifty unread statements the rest is counted rather than listed, but never dropped. */
    @Test
    void countsTheUnreadStatementsItStopsListing() {
        StringBuilder file = new StringBuilder("//CAPJOB   JOB  (ACCT),'X'\n");
        for (int i = 1; i <= 61; i++) {
            file.append(String.format("//BAD%03d   FROBNICATE ALL%n", i));
        }
        JclJobModel model = parsed(decodedSource("cap.jcl", file.toString()));

        assertEquals(51, syntax(model).size(), "fifty statements listed and one line counting the rest");
        assertEquals("これ以降の 11 文も解析できませんでした。",
                syntax(model).get(50).message());
        assertEquals(52, syntax(model).get(50).location().line());
    }

    /** A source filed with line numbers in columns 73-80 is read as if they were not there. */
    @Test
    void readsASequenceNumberedSource() throws Exception {
        JclJobModel model = parsed(decodedSource(construct("CJ509.jcl")));

        assertEquals("CJ509", model.jobName());
        assertEquals(List.of("STEP010"), model.steps().stream().map(JclStep::name).toList());
        assertEquals(List.of(), syntaxLines(model));
    }

    /** JCL written in lower case is read in upper case, and its quoted strings are left alone. */
    @Test
    void readsALowercaseSource() throws Exception {
        JclJobModel model = parsed(decodedSource(construct("CJ510.jcl")));

        assertEquals("CJ510", model.jobName());
        assertEquals(List.of("STEP010"), model.steps().stream().map(JclStep::name).toList());
        assertEquals(List.of("INFILE", "OUTFILE"), step(model, "STEP010").ddStatements().stream()
                .map(JclDdStatement::ddName).toList());
        assertEquals(List.of(), syntaxLines(model));
    }

    /** An ISPF skeleton writes a dialog symbol in the name field; the job is read all the same. */
    @Test
    void readsAJobWhoseNameFieldHoldsASymbol() throws Exception {
        List<JclJobModel> jobs = parsedJobs(decodedSource(construct("CS501.jcl")));

        assertEquals(List.of("&ZUSER.J", "&ZUSER.J"),
                jobs.stream().map(JclJobModel::jobName).toList());
        assertEquals(List.of("STEP0&X"),
                jobs.get(1).steps().stream().map(JclStep::name).toList());
        assertTrue(jobs.get(0).schedulerVariables().contains("&ZUSER."),
                jobs.get(0).schedulerVariables().toString());
    }

    /** A job group is read as one statement, so it costs one diagnostic rather than five. */
    @Test
    void readsAJobGroupAsOneStatement() throws Exception {
        List<JclJobModel> jobs = parsedJobs(decodedSource(construct("CJ505.jcl")));

        assertEquals(List.of("J1", "J2"), jobs.stream().map(JclJobModel::jobName).toList());
        assertEquals(List.of(1, 14, 20), syntaxLines(jobs.get(0)),
                "the group is one line reported, and the two SCHEDULE statements the other two");
    }

    /** An INCLUDE at job level between two steps contributes its steps where it stands. */
    @Test
    void expandsAnIncludeWrittenBetweenTwoSteps() {
        DecodedSource member = decodedSource("proclib/CI302.inc", String.join("\n",
                "//MIDSTEP  EXEC PGM=SYK050",
                "//MIDIN    DD   DSN=SYKT.MID,DISP=SHR",
                ""));
        String job = String.join("\n",
                "//MIDJOB   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=SYK001",
                "//         INCLUDE MEMBER=CI302",
                "//STEP020  EXEC PGM=SYK002",
                "");

        JclJobModel model = parsed(decodedSource("midjob.jcl", job),
                library(Map.of("CI302", member)));

        assertEquals(List.of("STEP010", "MIDSTEP", "STEP020"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "MIDSTEP"), "MIDIN", "SYKT.MID", 2);
    }

    /**
     * A PROC runs where the EXEC that calls it stands, so its steps come before the steps an
     * INCLUDE written under that EXEC contributes — and a referback written in the member finds
     * the PROC step it names.
     */
    @Test
    void putsTheProcsStepsBeforeTheStepsOfAnIncludeWrittenUnderTheCall() {
        DecodedSource member = decodedSource("proclib/CI310.inc", String.join("\n",
                "//INCSTEP  EXEC PGM=SYK070",
                "//INCIN    DD   DSN=*.STEP020.STEP1.OUT,DISP=SHR",
                ""));
        DecodedSource proc = decodedSource("proclib/PRC1.proc", String.join("\n",
                "//PRC1     PROC",
                "//STEP1    EXEC PGM=CCP001",
                "//OUT      DD   DSN=CJ.PROC.OUT,DISP=(NEW,CATLG)",
                "//         PEND",
                ""));
        String job = String.join("\n",
                "//INCJOB   JOB  (ACCT),'X'",
                "//STEP020  EXEC PRC1",
                "//         INCLUDE MEMBER=CI310",
                "");

        JclJobModel model = parsed(decodedSource("incjob.jcl", job),
                library(Map.of("CI310", member, "PRC1", proc)));

        assertEquals(List.of("STEP020", "STEP020.STEP1", "INCSTEP"),
                model.steps().stream().map(JclStep::name).toList());
        assertEquals("CJ.PROC.OUT",
                dd(step(model, "INCSTEP"), "INCIN").dataset().orElseThrow().name());
        assertEquals(List.of(), model.unresolvedReferbacks());
    }

    /** A member may hold a PROC of its own, which the step that INCLUDEs the member then EXECs. */
    @Test
    void expandsAProcDefinedInAnIncludedMember() {
        DecodedSource member = decodedSource("proclib/CI303.inc", String.join("\n",
                "//INCPRC   PROC HLQ=SYKW",
                "//PRCSTEP  EXEC PGM=SYK060",
                "//PRCOUT   DD   DSN=&HLQ..OUT,DISP=(NEW,CATLG)",
                "//         PEND",
                ""));
        String job = String.join("\n",
                "//PRCJOB   JOB  (ACCT),'X'",
                "//         INCLUDE MEMBER=CI303",
                "//STEP010  EXEC INCPRC,HLQ=SYKV",
                "");

        JclJobModel model = parsed(decodedSource("prcjob.jcl", job),
                library(Map.of("CI303", member)));

        assertEquals(List.of("STEP010", "STEP010.PRCSTEP"),
                model.steps().stream().map(JclStep::name).toList());
        assertDd(step(model, "STEP010.PRCSTEP"), "PRCOUT", "SYKV.OUT", 3);
    }

    /** A broken statement of a two-job file is reported once, under the job it stands in. */
    @Test
    void reportsABrokenStatementUnderOneJobOnly() {
        String file = String.join("\n",
                "//JOBA     JOB  (ACCT),'A'",
                "//BADSTMT  FROBNICATE ALL",
                "//STEP010  EXEC PGM=SYK001",
                "//JOBB     JOB  (ACCT),'B'",
                "//STEP020  EXEC PGM=SYK002",
                "");
        List<JclJobModel> jobs = parsedJobs(decodedSource("twojobs.jcl", file));

        assertEquals(List.of(2), syntaxLines(jobs.get(0)));
        assertEquals(List.of(), syntaxLines(jobs.get(1)),
                "the file's own diagnostics are carried by its first job, not by every job");
    }

    private static List<Path> jclFilesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jcl") || name.endsWith(".proc");
                    })
                    .filter(MapaJclParserTest::readableAsUtf8)
                    .sorted().toList();
        }
    }

    /**
     * Whether the file is written in UTF-8. A benchmark fixture filed in EBCDIC is decoded by the
     * pipeline's own code-page detection, which this test does not have and does not need.
     */
    private static boolean readableAsUtf8(Path file) {
        try {
            Files.readString(file, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Every JCL of one directory as a member library, which is what the pipeline builds. */
    private static JclMemberResolver directoryLibrary(Path directory) {
        Map<String, DecodedSource> members = new java.util.LinkedHashMap<>();
        try {
            for (Path file : jclFilesUnder(directory)) {
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                members.put((dot <= 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT),
                        decodedSource(file));
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return library(members);
    }

    /** The jobs with their diagnostics left out: the two paths report differently by design. */
    private static List<JclJobModel> withoutDiagnostics(List<JclJobModel> jobs) {
        return jobs.stream().map(job -> new JclJobModel(job.jobName(), job.sourceFile(),
                job.condition(), job.steps(), List.of(), job.members(), job.jcllib(),
                job.schedulerVariables(), job.parameters(), job.joblib(), job.syschk(),
                job.jes2Cards(), job.unresolvedSymbols(), job.outputStatements(),
                job.unresolvedOverrides(), job.unresolvedReferbacks(), job.missingMembers(),
                job.position())).toList();
    }

    private static List<Finding> syntax(JclJobModel model) {
        return model.diagnostics().stream()
                .filter(f -> Finding.JCL_SYNTAX_RULE_ID.equals(f.ruleId())).toList();
    }

    private static List<Integer> syntaxLines(JclJobModel model) {
        return syntax(model).stream().map(f -> f.location().line()).toList();
    }

    // ---- IF guards ----

    /**
     * A step in the ELSE branch runs when the test does not hold, so its guard says ELSE. The whole
     * nest guards a step, outermost first.
     */
    @Test
    void aStepTakesTheWholeIfNestAsItsCondition() {
        JclJobModel job = parsed(decodedSource("IFNEST.jcl", String.join("\n",
                "//IFNEST   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//         IF (STEP010.RC = 0) THEN",
                "//            IF (STEP010.RC = 0) THEN",
                "//STEP020  EXEC PGM=CCP002",
                "//            ELSE",
                "//STEP030  EXEC PGM=CCP003",
                "//            ENDIF",
                "//         ELSE",
                "//STEP040  EXEC PGM=CCP004",
                "//         ENDIF",
                "") + "\n"));

        assertEquals(Optional.of("IF (STEP010.RC = 0) THEN IF (STEP010.RC = 0) THEN"),
                step(job, "STEP020").condition());
        assertEquals(Optional.of("IF (STEP010.RC = 0) THEN IF (STEP010.RC = 0) ELSE"),
                step(job, "STEP030").condition(), "the inner ELSE branch");
        assertEquals(Optional.of("IF (STEP010.RC = 0) ELSE"), step(job, "STEP040").condition(),
                "the outer ELSE branch, the inner IF having been closed");
    }

    /** The name field of an IF statement labels the statement; the guard is the test alone. */
    @Test
    void takesTheGuardFromTheIfKeywordOnwards() {
        JclJobModel job = parsed(decodedSource("IFLBL.jcl", String.join("\n",
                "//IFLBL    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//LBL      IF (STEP010.RC = 0) THEN",
                "//STEP020  EXEC PGM=CCP002",
                "//         ENDIF",
                "") + "\n"));

        assertEquals(Optional.of("IF (STEP010.RC = 0) THEN"), step(job, "STEP020").condition(),
                "the label LBL takes no part in the test");
    }

    /**
     * The blanks the author wrote inside the test are kept: without them AND runs into the step
     * name after it, and a reader of the condition — the rule that checks the step names an IF
     * references included — is left with a name the job never wrote.
     */
    @Test
    void keepsTheBlanksWrittenInsideAnIfTest() {
        JclJobModel job = parsed(decodedSource("IFAND.jcl", String.join("\n",
                "//IFAND    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//STEP020  EXEC PGM=CCP002",
                "//         IF (STEP010.RC = 0 AND STEP020.RUN) THEN",
                "//STEP030  EXEC PGM=CCP003",
                "//         ENDIF",
                "") + "\n"));

        assertEquals(Optional.of("IF (STEP010.RC = 0 AND STEP020.RUN) THEN"),
                step(job, "STEP030").condition());
    }

    /** A test written across a continuation is run back together, the {@code //} standing for a
     * blank. */
    @Test
    void runsAContinuedIfTestBackTogether() {
        JclJobModel job = parsed(decodedSource("IFCONT.jcl", String.join("\n",
                "//IFCONT   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//         IF (STEP010.RC = 0 AND",
                "//            STEP010.RUN) THEN",
                "//STEP020  EXEC PGM=CCP002",
                "//         ENDIF",
                "") + "\n"));

        assertEquals(Optional.of("IF (STEP010.RC = 0 AND STEP010.RUN) THEN"),
                step(job, "STEP020").condition());
    }

    /**
     * An IF nobody can read still guards its steps, and says so: the test stands in the condition as
     * unread, rather than the step reading as one nothing guards.
     */
    @Test
    void showsAnUnreadableGuardAsATestNobodyRead() {
        JclJobModel job = parsed(decodedSource("IFBAD.jcl", String.join("\n",
                "//IFBAD    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//         IF (NOT STEP010.RC = 0) THEN",
                "//STEP020  EXEC PGM=CCP002",
                "//         ELSE",
                "//STEP030  EXEC PGM=CCP003",
                "//         ENDIF",
                "") + "\n"));

        assertEquals(List.of(3), syntaxLines(job), "the IF is the statement nobody read");
        assertEquals(Optional.of("IF(?)THEN"), step(job, "STEP020").condition());
        assertEquals(Optional.of("IF(?)ELSE"), step(job, "STEP030").condition(),
                "the ELSE branch of a guard nobody read is still the other branch of it");
    }

    /**
     * An IF the parser cannot read guards its steps with a test nobody read, and keeps its place all
     * the same, so the ELSE and the ENDIF below it do not close the IF around it. CJ109 writes one.
     */
    @Test
    void anUnreadableIfLeavesTheGuardsAroundItStanding() throws Exception {
        JclJobModel job = parsed(decodedSource(construct("CJ109.jcl")),
                directoryLibrary(construct("CJ109.jcl").getParent()));

        assertEquals(List.of(17), syntaxLines(job),
                "the outer IF, whose NOT stands inside the parenthesis");
        assertEquals(Optional.of("IF(?)THEN IF (STEP010.STEP1.RC <= 4) THEN "
                        + "IF (STEP010.RUN AND (STEP010.STEP1.RC = 0 OR "
                        + "STEP010.STEP1.RC = 4)) THEN"),
                step(job, "STEP020").condition(), "the whole nest, the unread test at the head");
        assertEquals(Optional.of("IF(?)THEN IF (STEP010.STEP1.RC <= 4) ELSE"),
                step(job, "STEP024").condition(),
                "the ELSE of the IF that was read, the one below it having closed");
        assertEquals(Optional.of("IF(?)ELSE IF (STEP010.STEP1.ABENDCC = S0C7) THEN"),
                step(job, "STEP022").condition());
        assertEquals(Optional.of("IF(?)ELSE IF (STEP010.STEP1.ABENDCC = S0C7) ELSE"),
                step(job, "STEP023").condition());
        assertEquals(Optional.of("COND=(4,LT,STEP010.STEP1)"), step(job, "STEP050").condition(),
                "a step outside the nest keeps its own COND");
    }

    // ---- PROC overrides ----

    /** A DD override names its step or the PROC's first; one the PROC has no DD for is added. */
    @Test
    void writesEveryFormOfDdOverrideIntoTheProcsSteps() {
        JclJobModel job = parsed(decodedSource("OVR.jcl", String.join("\n",
                "//OVR      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//INDD     DD   DSN=NEW.IN,DISP=SHR",
                "//STEP2.OUTDD DD DCB=BLKSIZE=27920",
                "//STEP1.ADDED DD SYSOUT=*",
                "//STEP9.NOSUCH DD DUMMY",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//INDD     DD   DSN=OLD.IN,DISP=OLD",
                        "//STEP2    EXEC PGM=CCP002",
                        "//OUTDD    DD   DSN=OLD.OUT,DISP=(NEW,CATLG),",
                        "//             DCB=(RECFM=FB,LRECL=80)",
                        "") + "\n"))));

        JclStep first = step(job, "STEP010.STEP1");
        assertEquals(Optional.of("NEW.IN"), dd(first, "INDD").datasetName(),
                "an unqualified override reaches the PROC's first step");
        assertEquals(Optional.of("SHR"), dd(first, "INDD").dispositionText());
        assertEquals(Optional.of("*"), dd(first, "ADDED").sysout(), "a DD the PROC has not is added");

        JclDdStatement outdd = dd(step(job, "STEP010.STEP2"), "OUTDD");
        assertEquals(Optional.of("OLD.OUT"), outdd.datasetName(), "the PROC's own DSN stands");
        assertEquals("(RECFM=FB,LRECL=80,BLKSIZE=27920)", outdd.parameters().get("DCB"),
                "one DCB subparameter overridden leaves the others the PROC coded");
        assertEquals(List.of("STEP9.NOSUCH"),
                job.unresolvedOverrides().stream().map(miss -> miss.text()).toList());
        assertEquals(List.of("STEP9.NOSUCH"), step(job, "STEP010").ddStatements().stream()
                        .map(JclDdStatement::ddName).toList(),
                "an override no step of the PROC could take stays on the call step; the rest do not");
    }

    /**
     * A DD override naming a procstep the PROC does not have stays on the call step under the name
     * the author wrote, which is what the unexpanded path does with an override too.
     */
    @Test
    void keepsADdOverrideNamingAStepTheProcLacksOnTheCallStep() {
        JclJobModel job = parsed(decodedSource("BADSTEP.jcl", String.join("\n",
                "//BADSTEP  JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//BADSTEP.SYSIN DD DUMMY",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//SYSIN    DD   DUMMY",
                        "") + "\n"))));

        JclDdStatement kept = dd(step(job, "STEP010"), "BADSTEP.SYSIN");
        assertEquals(3, kept.position().line());
        assertTrue(kept.dummy());
        assertEquals(List.of("BADSTEP.SYSIN"),
                job.unresolvedOverrides().stream().map(JclOverrideMiss::text).toList(),
                "and it is reported as an override nothing took");
    }

    /**
     * The JCL Reference matches an overriding DD statement against the concatenation entry by
     * entry: entry i takes override i, and an entry no override was written for stands as the PROC
     * coded it.
     */
    @Test
    void matchesEachOverrideAgainstTheConcatenationEntryItStandsFor() {
        JclJobModel job = parsed(decodedSource("CAT.jcl", String.join("\n",
                "//CAT      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.LIB DD DSN=NEW.ONE,DISP=SHR",
                "//         DD   DSN=NEW.TWO,DISP=SHR",
                "//STEP1.NEWCAT DD DSN=ADD.ONE,DISP=SHR",
                "//         DD   DSN=ADD.TWO,DISP=SHR",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//LIB      DD   DSN=OLD.ONE,DISP=SHR",
                        "//         DD   DSN=OLD.TWO,DISP=SHR",
                        "//         DD   DSN=OLD.THREE,DISP=SHR",
                        "") + "\n"))));

        List<JclDdStatement> dds = step(job, "STEP010.STEP1").ddStatements();
        assertEquals(List.of("LIB 0 NEW.ONE", "LIB 1 NEW.TWO", "LIB 2 OLD.THREE",
                        "NEWCAT 0 ADD.ONE", "NEWCAT 1 ADD.TWO"),
                dds.stream().map(d -> d.ddName() + " " + d.concatIndex() + " "
                        + d.datasetName().orElse("-")).toList(),
                "the entry the call wrote no override for is the one the PROC coded");
    }

    /** An overriding DD statement past the last entry of the concatenation is added after it. */
    @Test
    void concatenatesAnOverrideWrittenPastTheProcsLastEntry() {
        JclJobModel job = parsed(decodedSource("CAT2.jcl", String.join("\n",
                "//CAT2     JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.LIB DD DSN=NEW.ONE,DISP=SHR",
                "//         DD   DSN=NEW.TWO,DISP=SHR",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//LIB      DD   DSN=OLD.ONE,DISP=SHR",
                        "") + "\n"))));

        assertEquals(List.of("LIB 0 NEW.ONE", "LIB 1 NEW.TWO"),
                step(job, "STEP010.STEP1").ddStatements().stream()
                        .map(d -> d.ddName() + " " + d.concatIndex() + " "
                                + d.datasetName().orElse("-")).toList());
    }

    /** DSN and DSNAME are one parameter: an override coding either takes the PROC's place. */
    @Test
    void takesADsnameOverrideOverTheProcsDsn() {
        JclJobModel job = parsed(decodedSource("DSNO.jcl", String.join("\n",
                "//DSNO     JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.INDD DD DSNAME=CJ.NEW.IN",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//INDD     DD   DSN=CJ.OLD.IN,DISP=SHR",
                        "") + "\n"))));

        assertEquals(Optional.of("CJ.NEW.IN"),
                dd(step(job, "STEP010.STEP1"), "INDD").datasetName());
    }

    /**
     * The JCL Reference: an unqualified PARM reaches the PROC's first step and nullifies the PARM of
     * every other; every other unqualified keyword reaches every step of the PROC.
     */
    @Test
    void writesAnUnqualifiedExecOverrideIntoTheStepsTheReferenceNames() {
        JclJobModel job = parsed(decodedSource("EXO.jcl", String.join("\n",
                "//EXO      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1,PARM='CALLER',COND=(4,LT)",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001,PARM='OWN1'",
                        "//STEP2    EXEC PGM=CCP002,PARM='OWN2',COND=(0,NE,STEP1)",
                        "") + "\n"))));

        assertEquals(Optional.of("CALLER"), step(job, "STEP010.STEP1").parm());
        assertEquals(Optional.empty(), step(job, "STEP010.STEP2").parm(),
                "an unqualified PARM nullifies the PARM of every step after the first");
        assertEquals(Optional.of("COND=(4,LT)"), step(job, "STEP010.STEP1").condition());
        assertEquals(Optional.of("COND=(4,LT)"), step(job, "STEP010.STEP2").condition(),
                "an unqualified COND replaces the COND the PROC coded on every step");
    }

    /** An override of a PROC nothing expanded stays where it was written, and is reported. */
    @Test
    void keepsTheOverridesOfAProcNothingExpanded() {
        JclJobModel job = parsed(decodedSource("NOPRC.jcl", String.join("\n",
                "//NOPRC    JOB  (ACCT),'X'",
                "//STEP010  EXEC MISSING,COND.STEP1=(4,LT)",
                "//STEP1.INDD DD DSN=A.B,DISP=SHR",
                "") + "\n"));

        assertEquals(Optional.of("A.B"), dd(step(job, "STEP010"), "STEP1.INDD").datasetName(),
                "the row stays on the call step, named as the author wrote it");
        assertEquals(List.of("STEP1.INDD", "COND.STEP1"),
                job.unresolvedOverrides().stream().map(JclOverrideMiss::text).toList(),
                "each of them is reported as an override nothing took");
    }

    // ---- job-level fields ----

    /** JOBLIB, SYSCHK, the OUTPUT statements and JCLLIB resolve against the job's own symbols. */
    @Test
    void resolvesTheSymbolsOfEveryJobLevelField() {
        JclJobModel job = parsed(decodedSource("JOBLVL.jcl", String.join("\n",
                "//JOBLVL   JOB  (ACCT),'X'",
                "//         JCLLIB ORDER=(&HLQ..PROCLIB)",
                "//JOBLIB   DD   DSN=&HLQ..LOADLIB,DISP=SHR",
                "//         DD   DSN=&HLQ..LOADLB2,DISP=SHR",
                "//SYSCHK   DD   DSN=&HLQ..CHKPT,DISP=OLD",
                "//OUT1     OUTPUT DEST=&DEST,COPIES=1",
                "//         SET HLQ=FL.PROD,DEST=RMT3",
                "//STEP010  EXEC PGM=CCP001",
                "") + "\n"));

        assertEquals(List.of("FL.PROD.PROCLIB"), job.jcllib());
        assertEquals(List.of("FL.PROD.LOADLIB", "FL.PROD.LOADLB2"),
                job.joblib().stream().map(d -> d.datasetName().orElseThrow()).toList());
        assertEquals(Optional.of("FL.PROD.CHKPT"),
                job.syschk().orElseThrow().datasetName(), "the job's checkpoint data set");
        assertEquals("RMT3", job.outputStatements().get("OUT1").get("DEST"));
        assertEquals(List.of(), job.unresolvedSymbols());
    }

    /** A value the lexer hides the apostrophes and the commas of is kept as the author wrote it. */
    @Test
    void keepsAParameterValueAsItWasWritten() {
        JclJobModel job = parsed(decodedSource("QUOTED.jcl", String.join("\n",
                "//QUOTED   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//VSAMDS   DD   DSN=A.VSAM,DISP=SHR,",
                "//             AMP=('BUFND=10','BUFNI=5')",
                "//UNIXOUT  DD   PATH='/u/prod/out.dat',FILEDATA=TEXT",
                "//DCBDS    DD   DSN=A.B,DISP=SHR,DCB=(RECFM=FB,LRECL=80)",
                "") + "\n"));

        JclStep step = step(job, "STEP010");
        assertEquals("('BUFND=10','BUFNI=5')", dd(step, "VSAMDS").parameters().get("AMP"));
        assertEquals("'/u/prod/out.dat'", dd(step, "UNIXOUT").parameters().get("PATH"));
        assertEquals("(RECFM=FB,LRECL=80)", dd(step, "DCBDS").parameters().get("DCB"));
    }

    /** A referback naming a DD that is itself one follows the chain to the data set behind it. */
    @Test
    void followsAChainOfReferbacks() {
        JclJobModel job = parsed(decodedSource("CHAIN.jcl", String.join("\n",
                "//CHAIN    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//DD1      DD   DSN=A.REAL.DSN,DISP=SHR",
                "//DD2      DD   DSN=*.DD1,DISP=SHR",
                "//DD3      DD   DSN=*.DD2,DISP=SHR",
                "//STEP020  EXEC PGM=CCP002",
                "//DD4      DD   DSN=*.STEP010.DD3,DISP=SHR",
                "//DD5      DD   DSN=*.STEP099.DD1,DISP=SHR",
                "") + "\n"));

        assertEquals("A.REAL.DSN",
                dd(step(job, "STEP010"), "DD3").dataset().orElseThrow().name());
        assertEquals("A.REAL.DSN",
                dd(step(job, "STEP020"), "DD4").dataset().orElseThrow().name(),
                "a chain followed across steps");
        assertEquals(List.of("*.STEP099.DD1"),
                job.unresolvedReferbacks().stream().map(miss -> miss.text()).toList(),
                "a referback naming a step that is not there is reported");
    }

    /** DSN=NULLFILE names no data set, which is what DUMMY says; the omitted DISP status is NEW. */
    @Test
    void readsNullfileAsADummyAndAnOmittedDispStatusAsNew() {
        JclJobModel job = parsed(decodedSource("NULLF.jcl", String.join("\n",
                "//NULLF    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=IEFBR14",
                "//NULLOUT  DD   DSN=NULLFILE",
                "//PASSDS   DD   DSN=&&TEMP,DISP=(,PASS),UNIT=SYSDA,SPACE=(TRK,(1,1))",
                "") + "\n"));

        JclStep step = step(job, "STEP010");
        assertTrue(dd(step, "NULLOUT").dummy(), "NULLFILE names no data set");
        assertTrue(dd(step, "NULLOUT").dataset().isEmpty());
        assertEquals(Optional.empty(), dd(step, "NULLOUT").datasetName(),
                "and no name either, so nothing downstream takes NULLFILE for a data set");
        assertEquals("NULLFILE", dd(step, "NULLOUT").parameters().get("DSN"),
                "what the author wrote stands in the parameters all the same");
        assertEquals("NEW", dd(step, "PASSDS").disposition().orElseThrow().status());
        assertEquals(Optional.of("PASS"), dd(step, "PASSDS").disposition().orElseThrow().normal());
    }

    // ---- continuation ----

    /**
     * Column 72 is the continuation flag, never a character of the operand. CJ105 breaks a quoted
     * PARM at column 71 with an X in column 72, so a reader who ran the lines together would read
     * {@code LOXGLEVEL} where the author wrote {@code LOGLEVEL}.
     */
    @Test
    void leavesTheContinuationFlagOutOfTheValueItInterrupts() throws Exception {
        JclJobModel job = parsed(decodedSource(construct("CJ105.jcl")));

        assertEquals(Optional.of("CYCLE=250911,MODE='BATCH',DEBUG=N,LOGLEVEL=INFO,RETRY=3,"
                        + "OUTPUT=SUMMARY,VERBOSE=Y,STATUS=OK"),
                step(job, "STEP020").parm());
        assertEquals(List.of(), syntaxLines(job));
    }

    /**
     * An operand no rule of the grammar reads is kept on the keyword the rule before it stopped
     * inside, and the keyword is reported: the value stands in the model as the author wrote it, and
     * a reader is told nobody read it apart. A DD keyword this grammar does not know is one such
     * operand, and so is a comma with nothing after it.
     */
    @Test
    void keepsAnOperandNoRuleReadOnTheKeywordBeforeIt() {
        JclJobModel job = parsed(decodedSource("SALV.jcl", String.join("\n",
                "//SALV     JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//DD1      DD   DSN=A.B,DISP=SHR,QNAME=TPPROCESS",
                "//DD2      DD   DSN=A.C,DISP=SHR,DCB=(RECFM=FB,LRECL=80),,",
                "") + "\n"));

        assertEquals("SHR,QNAME=TPPROCESS", dd(step(job, "STEP010"), "DD1").parameters().get("DISP"),
                "the operand stands on the keyword the grammar stopped inside");
        assertEquals("(RECFM=FB,LRECL=80),,",
                dd(step(job, "STEP010"), "DD2").parameters().get("DCB"));
        assertEquals(List.of("3 行の JCL 文のパラメーター DISP を解析できませんでした。値は原文のまま保持します。",
                        "4 行の JCL 文のパラメーター DCB を解析できませんでした。値は原文のまま保持します。"),
                job.diagnostics().stream().map(Finding::message).toList());
    }

    /** The same, for CJ511, whose literal is broken mid-word with an X in column 72. */
    @Test
    void leavesTheContinuationFlagOutOfALiteralBrokenMidWord() throws Exception {
        JclJobModel job = parsed(decodedSource(construct("CJ511.jcl")));

        assertEquals(Optional.of("CJ511 CONTINUATION DEMO PARM BROKEN MID LITERAL AND CLOSED HERE"),
                step(job, "STEP010").parm());
        assertEquals("SER=(VOL001,VOL002,VOL003,VOL004)",
                dd(step(job, "STEP010"), "SYSUT2").parameters().get("VOL"),
                "and the volume list is whole, flag and all four serials");
        assertEquals(List.of(), job.diagnostics(), "and nothing about it went unread");
    }

    // ---- overrides that cancel one another ----

    /**
     * DUMMY and a data set name cancel one another whichever way round the call writes them, because
     * a step either reads a data set through the DD or reads nothing at all.
     */
    @Test
    void cancelsTheProcsDsnWithAnOverridesDummyAndTheOtherWayRound() {
        JclJobModel job = parsed(decodedSource("DUM.jcl", String.join("\n",
                "//DUM      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.REALDD DD DUMMY",
                "//STEP1.DUMDD DD DSN=NEW.REAL,DISP=SHR",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//REALDD   DD   DSN=OLD.REAL,DISP=(OLD,KEEP)",
                        "//DUMDD    DD   DUMMY",
                        "") + "\n"))));

        JclDdStatement dummied = dd(step(job, "STEP010.STEP1"), "REALDD");
        assertTrue(dummied.dummy(), "the override says the step reads nothing through the DD");
        assertEquals(Optional.empty(), dummied.datasetName(), "so the PROC's DSN is gone");
        assertEquals(Optional.empty(), dummied.dispositionText(), "and its DISP with it");

        JclDdStatement named = dd(step(job, "STEP010.STEP1"), "DUMDD");
        assertEquals(Optional.of("NEW.REAL"), named.datasetName());
        assertFalse(named.dummy(), "an override naming a data set cancels the PROC's DUMMY");
    }

    /** AMP is usually coded as one quoted string, and merges inside the apostrophes all the same. */
    @Test
    void mergesAnAmpWrittenAsOneQuotedString() {
        JclJobModel job = parsed(decodedSource("AMP.jcl", String.join("\n",
                "//AMP      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.VSAMDD DD AMP='BUFND=20'",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//VSAMDD   DD   DSN=A.VSAM,DISP=SHR,AMP='AMORG,BUFND=10'",
                        "") + "\n"))));

        assertEquals("'AMORG,BUFND=20'",
                dd(step(job, "STEP010.STEP1"), "VSAMDD").parameters().get("AMP"),
                "the override replaces BUFND and leaves AMORG standing");
    }

    /**
     * A merged DD stands on the line a reader would go to: the override's when it named the data set
     * or the disposition, the PROC's when it changed anything else.
     */
    @Test
    void takesTheMergedDdsLineFromWhicheverStatementNamesTheData() {
        JclJobModel job = parsed(decodedSource("POS.jcl", String.join("\n",
                "//POS      JOB  (ACCT),'X'",
                "//STEP010  EXEC PRC1",
                "//STEP1.DSNDD DD DSN=NEW.IN,DISP=SHR",
                "//STEP1.DCBDD DD DCB=BLKSIZE=27920",
                "") + "\n"), library(Map.of("PRC1", decodedSource("PRC1.proc", String.join("\n",
                        "//PRC1     PROC",
                        "//STEP1    EXEC PGM=CCP001",
                        "//DSNDD    DD   DSN=OLD.IN,DISP=OLD",
                        "//DCBDD    DD   DSN=OLD.OUT,DISP=SHR,DCB=(RECFM=FB,LRECL=80)",
                        "") + "\n"))));

        assertEquals(3, dd(step(job, "STEP010.STEP1"), "DSNDD").position().line(),
                "the override named the data set, so the row is on the override's line");
        assertEquals(4, dd(step(job, "STEP010.STEP1"), "DCBDD").position().line(),
                "the PROC still names the data set, so the row stays on the PROC's line");
    }

    /** An unqualified PARM descends into the PROC the first step calls, as a DD override does. */
    @Test
    void sendsAnUnqualifiedParmDownIntoTheProcTheFirstStepCalls() {
        JclJobModel job = parsed(decodedSource("PARMDOWN.jcl", String.join("\n",
                "//PARMDOWN JOB  (ACCT),'X'",
                "//STEP010  EXEC OUTER,PARM='CALLER'",
                "") + "\n"), library(Map.of(
                        "OUTER", decodedSource("OUTER.proc", String.join("\n",
                                "//OUTER    PROC",
                                "//CALL1    EXEC INNER",
                                "//STEP2    EXEC PGM=CCP002,PARM='OWN2'",
                                "") + "\n"),
                        "INNER", decodedSource("INNER.proc", String.join("\n",
                                "//INNER    PROC",
                                "//STEP1    EXEC PGM=CCP001,PARM='OWN1'",
                                "") + "\n"))));

        assertEquals(Optional.of("CALLER"), step(job, "STEP010.CALL1.STEP1").parm(),
                "the first step that runs a program takes the PARM");
        assertEquals(Optional.empty(), step(job, "STEP010.STEP2").parm(),
                "and every other step of the PROC loses its own");
    }

    /**
     * REFDD names the DD whose attributes the statement copies, which is a referback like any
     * other: it is recorded against the DD it points at, and one that points at nothing is
     * reported. The data set the statement names for itself is untouched.
     */
    @Test
    void recordsARefddAsAReferback() {
        JclJobModel job = parsed(decodedSource("REFDD.jcl", String.join("\n",
                "//REFDD    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//DD1      DD   DSN=A.MODEL,DISP=SHR",
                "//DD2      DD   DSN=A.NEW,DISP=(NEW,CATLG),REFDD=*.DD1",
                "//DD3      DD   DSN=A.NEW2,DISP=(NEW,CATLG),REFDD=*.DD9",
                "") + "\n"));

        assertEquals(Map.of("REFDD", "*.DD1"), dd(step(job, "STEP010"), "DD2").referbacks());
        assertEquals(Optional.of("A.NEW"), dd(step(job, "STEP010"), "DD2").datasetName(),
                "REFDD copies attributes, not the data set name");
        assertEquals(List.of("*.DD9"),
                job.unresolvedReferbacks().stream().map(miss -> miss.text()).toList());
    }

    /**
     * A comment written between two tokens of one parameter — which JCL allows inside a
     * parenthesised list continued onto the next line — belongs to no parameter: the operand field
     * of each line ends at the first blank outside apostrophes.
     */
    @Test
    void leavesAnInlineCommentOutOfAContinuedDcb() {
        JclJobModel job = parsed(decodedSource("DCBCMT.jcl", String.join("\n",
                "//DCBCMT   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//DD1      DD   DSN=A.OUT,DISP=(NEW,CATLG),DCB=(RECFM=FB,   SEE THE STANDARD",
                "//             LRECL=80)",
                "") + "\n"));

        assertEquals("(RECFM=FB,LRECL=80)",
                dd(step(job, "STEP010"), "DD1").parameters().get("DCB"));
    }

    /**
     * A value the grammar refuses is stood in for by a name of the same length, and that name is
     * put back into every string of the finished model; a name another statement of the file uses
     * is therefore no name to stand in with.
     */
    @Test
    void keepsASalvagedStandInClearOfTheNamesTheFileUses() {
        JclJobModel job = parsed(decodedSource("STANDIN.jcl", String.join("\n",
                "//STANDIN  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//MODELDS  DD DSN=A.MODELED,",
                "//            DISP=(NEW,CATLG,DELETE),",
                "//            DCB=MODEL.DSCB",
                "//STEP020  EXEC PGM=CCP002,PARM='Z1ZZZZZZZZ'",
                "") + "\n"));

        assertTrue(syntax(job).get(0).message().contains("パラメーター DCB"),
                syntax(job).toString());
        assertEquals(Optional.of("MODEL.DSCB"), JclParameters.value(
                dd(step(job, "STEP010"), "MODELDS").parameters(), "DCB"),
                "the value the grammar refused stands in the model as the author wrote it");
        assertEquals(Optional.of("Z1ZZZZZZZZ"), step(job, "STEP020").parm(),
                "and the stand-in it was read with was no name this file uses itself");
    }

    /** A referback may name the step it stands in, and resolves against that step's own DD rows. */
    @Test
    void resolvesAReferbackNamingTheStepItStandsIn() {
        JclJobModel job = parsed(decodedSource("SELFREF.jcl", String.join("\n",
                "//SELFREF  JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//DD1      DD   DSN=A.REAL.DSN,DISP=SHR",
                "//DD2      DD   DSN=*.STEP010.DD1,DISP=SHR",
                "") + "\n"));

        assertEquals("A.REAL.DSN",
                dd(step(job, "STEP010"), "DD2").dataset().orElseThrow().name());
        assertEquals(List.of(), job.unresolvedReferbacks());
    }


    // ---- what the library does not hold ----

    /**
     * A PROC an EXEC calls and a member an INCLUDE brings in are both taken down when the library
     * holds neither: the model says which statement named what, because the steps the member would
     * have contributed are missing from it.
     */
    @Test
    void namesEveryMemberTheLibraryDoesNotHold() {
        JclJobModel job = parsed(decodedSource("MISS.jcl", String.join("\n",
                "//MISS     JOB  (ACCT),'X'",
                "//         INCLUDE MEMBER=NOSUCH1",
                "//STEP010  EXEC NOSUCH2",
                "//STEP020  EXEC PGM=CCP001",
                "//         INCLUDE MEMBER=NOSUCH3",
                "") + "\n"));

        assertEquals(List.of("2 INCLUDE NOSUCH1", "3 PROC NOSUCH2", "4 INCLUDE NOSUCH3"),
                job.missingMembers().stream()
                        .map(miss -> miss.line() + " " + miss.kind() + " " + miss.name()).toList());
        assertEquals(List.of("STEP010", "STEP020"),
                job.steps().stream().map(JclStep::name).toList(),
                "the call step stays, with nothing of the PROC under it");
    }

    /** A job stands where its JOB card stands, so a finding about the job points at that line. */
    @Test
    void standsWhereItsJobCardStands() {
        List<JclJobModel> jobs = parsedJobs(decodedSource("TWO.jcl", String.join("\n",
                "//*  a comment before the first JOB card",
                "//*",
                "//FIRST    JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP001",
                "//SECOND   JOB  (ACCT),'X'",
                "//STEP010  EXEC PGM=CCP002",
                "") + "\n"));

        assertEquals(List.of(3, 5), jobs.stream().map(job -> job.position().line()).toList());
        assertEquals("TWO.jcl", jobs.get(0).position().file());
    }

    private static JclJobModel parsed(DecodedSource source) {
        return parsed(source, JclMemberResolver.NONE);
    }

    private static JclJobModel parsed(DecodedSource source, JclMemberResolver members) {
        return parsedJobs(source, members).get(0);
    }

    private static List<JclJobModel> parsedJobs(DecodedSource source) {
        return parsedJobs(source, JclMemberResolver.NONE);
    }

    private static List<JclJobModel> parsedJobs(DecodedSource source, JclMemberResolver members) {
        ParseOutcome<List<JclJobModel>> outcome = new MapaJclParser().parse(source, members);
        assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
        return outcome.value().orElseThrow();
    }
}
