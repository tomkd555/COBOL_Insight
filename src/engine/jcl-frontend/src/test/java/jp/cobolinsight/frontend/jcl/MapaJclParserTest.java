package jp.cobolinsight.frontend.jcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
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
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] offsets = new int[text.length()];
        int byteOffset = 0;
        for (int i = 0; i < text.length(); i++) {
            offsets[i] = byteOffset;
            byteOffset += String.valueOf(text.charAt(i)).getBytes(StandardCharsets.UTF_8).length;
        }
        return new DecodedSource(path, text, bytes, offsets,
                new EncodingInfo("UTF-8", 1.0, false, false));
    }

    private static JclJobModel parseSample(String fileName) throws IOException {
        Path file = REPO_ROOT.resolve("samples").resolve("jcl").resolve(fileName);
        ParseOutcome<JclJobModel> outcome = new MapaJclParser().parse(decodedSource(file), List.of());
        assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
        return outcome.value().orElseThrow();
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
    void expandsCataloguedProcFromTheProcedureLibrary(@TempDir Path library) throws Exception {
        Files.writeString(library.resolve("SYKPRC99.jcl"), String.join("\n",
                "//SYKPRC99 PROC CYCLE=000000",
                "//PRCSTEP  EXEC PGM=SYK099",
                "//PRCIN    DD   DSN=SYKW.D&CYCLE..STOCK.EXTRACT,DISP=SHR",
                "//         PEND",
                ""), StandardCharsets.UTF_8);
        String job = String.join("\n",
                "//CATJOB   JOB  (ACCT),'X',CLASS=A",
                "//         SET CYCLE=250718",
                "//STEP010  EXEC SYKPRC99,CYCLE=&CYCLE",
                "");

        ParseOutcome<JclJobModel> outcome = new MapaJclParser()
                .parse(decodedSource("catjob.jcl", job), List.of(library));

        assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
        JclJobModel model = outcome.value().orElseThrow();
        assertEquals(2, model.steps().size());
        JclStep expanded = step(model, "STEP010.PRCSTEP");
        assertEquals("SYK099", expanded.target());
        // A catalogued PROC's steps carry the line and the file of the PROC member.
        assertEquals(2, expanded.position().line());
        assertEquals(library.resolve("SYKPRC99.jcl").toString(), expanded.position().file());
        assertDd(expanded, "PRCIN", "SYKW.D250718.STOCK.EXTRACT", 3);
    }

    @Test
    void reportsFailureAsErrorFinding() {
        ParseOutcome<JclJobModel> outcome = new MapaJclParser()
                .parse(decodedSource("not-jcl.txt", "PLAIN TEXT, NOT JCL\n"), List.of());

        assertFalse(outcome.isSuccess());
        Finding finding = outcome.failureFinding().orElseThrow();
        assertEquals(Finding.PARSE_FAILURE_RULE_ID, finding.ruleId());
    }

    private static JclJobModel parsed(DecodedSource source) {
        ParseOutcome<JclJobModel> outcome = new MapaJclParser().parse(source, List.of());
        assertTrue(outcome.isSuccess(), () -> "parse failed: " + outcome.failureFinding());
        return outcome.value().orElseThrow();
    }
}
