package jp.cobolinsight.frontend.jcl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;

/** The pre-pass that puts a source into the shape the JCL lexer reads. */
class SchedulerMarkersTest {

    /** The markers over one source, with the statement map they take the data lines from. */
    private static SchedulerMarkers.Result apply(SchedulerMarkers markers, String text,
            String path) {
        return markers.apply(text, path, JclStatements.of(text));
    }

    /** Every line keeps its length, which is what keeps column 72 where the author put it. */
    private static void assertSameLengths(String before, String after) {
        String[] was = before.split("\n", -1);
        String[] is = after.split("\n", -1);
        assertEquals(was.length, is.length, "line count");
        for (int i = 0; i < was.length; i++) {
            assertEquals(was[i].length(), is[i].length(), "length of line " + (i + 1));
        }
    }

    @Test
    void turnsAForeignLineIntoACommentOfTheSameLength() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//JOB1     JOB  (ACCT),'X'",
                "#JI JOBNAME=JOB1",
                ")SEL &SYS = TSO",
                "++INCLUDE MEMBER1",
                "//STEP010  EXEC PGM=PGMA",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "marked.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("//*             ", lines.get(1));
        assertEquals("//*            ", lines.get(2));
        assertEquals("//*              ", lines.get(3));
        assertEquals("//STEP010  EXEC PGM=PGMA", lines.get(4));
        assertEquals(List.of(2, 3, 4),
                result.directives().stream().map(f -> f.location().line()).toList());
        assertEquals(FindingLevel.NOTE, result.directives().get(0).level());
        assertEquals(Finding.JCL_DIRECTIVE_RULE_ID, result.directives().get(0).ruleId());
        assertSameLengths(text, result.text());
    }

    @Test
    void replacesSchedulerTokensAndPutsThemBack() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = "//IN1      DD   DSN=SYKT.%%ODATE.ORDER.$ORUN,DISP=SHR\n";
        SchedulerMarkers.Result result = apply(markers, text, "tokens.jcl");

        assertEquals(List.of("%%ODATE", "$ORUN"), markers.variables());
        String dsn = result.text().substring(result.text().indexOf("DSN=") + 4).strip()
                .replace(",DISP=SHR", "");
        assertEquals("SYKT.%%ODATE.ORDER.$ORUN", markers.restore(dsn));
        assertEquals(List.of(), result.directives());
        assertSameLengths(text, result.text());
    }

    /**
     * A token written back to back with the text before it is put back all the same. The stand-in
     * is a symbolic parameter, and JCL lets one follow a name character, so only where it ends is
     * a boundary asked for.
     */
    @Test
    void putsBackATokenWrittenAgainstTheTextBeforeIt() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = "//OUTDS    DD   DSN=PREFIX.D%%ODATE,DISP=SHR\n";
        SchedulerMarkers.Result result = apply(markers, text, "inline.jcl");

        String dsn = result.text().substring(result.text().indexOf("DSN=") + 4).strip()
                .replace(",DISP=SHR", "");
        assertEquals("PREFIX.D%%ODATE", markers.restore(dsn));
        assertSameLengths(text, result.text());
    }

    /** An OPC SETVAR declares a variable of the scheduler, and names the ones it is filled from. */
    @Test
    void recordsTheVariablesASetvarDirectiveDeclares() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//*%OPC SETVAR TDATE=(OYMD1)",
                "//*>OPC SETVAR RUNDATE=D2026254",
                "//STEP010  EXEC PGM=IEFBR14",
                "") + "\n";
        apply(markers, text, "setvar.jcl");

        assertEquals(List.of("&TDATE", "&OYMD1", "&RUNDATE"), markers.variables());
        assertTrue(markers.declares("OYMD1"), "the variable the controller fills TDATE from");
        assertFalse(markers.declares("D2026254"), "a literal value declares nothing");
    }

    @Test
    void leavesCommentsAndInStreamDataAsWritten() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//*  %%ODATE はコメントの中では置き換えません",
                "//SYSIN    DD   *",
                "  DELETE SYKW.%%ODATE.WORK",
                "/*",
                "//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "data.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertTrue(lines.get(0).contains("%%ODATE"), lines.get(0));
        assertEquals("  DELETE SYKW.%%ODATE.WORK", lines.get(2));
        assertEquals("//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR",
                markers.restore(lines.get(4)), "the operand outside the data is rewritten");
        assertEquals(lines.get(4).length(), "//IN1      DD   DSN=SYKT.%%ODATE.ORDER,DISP=SHR"
                .length(), lines.get(4));
    }

    @Test
    void recordsTheOpcDirectivesItReadsPast() {
        SchedulerMarkers markers = new SchedulerMarkers();
        SchedulerMarkers.Result result = apply(markers, String.join("\n",
                "//*%OPC SCAN",
                "//*>OPC BEGIN",
                "//*  ordinary comment",
                ""), "opc.jcl");

        assertEquals(List.of(1, 2),
                result.directives().stream().map(f -> f.location().line()).toList());
    }

    /** A source filed with line numbers in columns 73-80 is read without them. */
    @Test
    void blanksTheSequenceNumbersOfAFiledSource() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                pad("//SEQJOB   JOB  (ACCT),'X'") + "00000010",
                pad("//STEP010  EXEC PGM=SYK001") + "00000020",
                pad("//") + "00000030",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "seq.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals(pad("//SEQJOB   JOB  (ACCT),'X'") + "        ", lines.get(0));
        assertEquals(pad("//") + "        ", lines.get(2));
        assertEquals(List.of(), result.directives(), "a filed source is no cause for a note");
        assertSameLengths(text, result.text());
    }

    /** One line whose columns 73-80 hold operand text stops the blanking for the whole source. */
    @Test
    void leavesTheSequenceColumnsAloneWhenOneLineWritesOperandThere() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                pad("//SEQJOB   JOB  (ACCT),'X'") + "00000010",
                pad("//IN1      DD   DSN=SYKT.IN,") + "DISP=SHR",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "seq.jcl");

        assertEquals(text, result.text());
    }

    /** Lower-case JCL is read in upper case, except inside a quoted string. */
    @Test
    void foldsALowercaseSourceToUpperCase() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//lcjob    job  (acct),'x'",
                "//*  a comment stays as written",
                "//step010  exec pgm=pgma,parm='Mixed Case Parm'",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "lower.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("//LCJOB    JOB  (ACCT),'x'", lines.get(0));
        assertEquals("//*  a comment stays as written", lines.get(1));
        assertEquals("//STEP010  EXEC PGM=PGMA,PARM='Mixed Case Parm'", lines.get(2));
        assertSameLengths(text, result.text());
    }

    /** A quoted string carried onto the next line keeps its case there too. */
    @Test
    void leavesAQuotedStringContinuedOntoTheNextLineAsWritten() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//lcjob    job  (acct),'x'",
                "//step010  exec pgm=pgma,parm='Mixed",
                "//             Case Parm'",
                "//infile   dd   dummy",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "continued.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals("//STEP010  EXEC PGM=PGMA,PARM='Mixed", lines.get(1));
        assertEquals("//             Case Parm'", lines.get(2));
        assertEquals("//INFILE   DD   DUMMY", lines.get(3),
                "the string closed on the line before, so this one is folded again");
    }

    /** A lower-case line a program reads as its own input is no vote on how the JCL was written. */
    @Test
    void doesNotFoldTheFileForALowercaseLineInsideInStreamData() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//CJ211    JOB  (CJ0001),'X'",
                "//STEP010  EXEC PGM=IEFBR14",
                "//CTLCARD  DD   DATA",
                "//this-looks-like-a-dd-statement dd dsn=sample,disp=shr",
                "/*",
                "//SYSPRINT DD   SYSOUT=*",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "instream.jcl");

        assertEquals(text, result.text(), "nothing in this file is written in lower case");
    }

    /** A banner ruled out past column 72 is no vote against a sequence-numbered source. */
    @Test
    void blanksTheSequenceNumbersDespiteALongCommentBanner() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String banner = "//*" + "-".repeat(77);
        String text = String.join("\n",
                pad("//SEQJOB   JOB  (ACCT),'X'") + "00000010",
                banner,
                pad("//STEP010  EXEC PGM=SYK001") + "00000020",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "banner.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals(pad("//SEQJOB   JOB  (ACCT),'X'") + "        ", lines.get(0));
        assertEquals(banner.substring(0, 72) + " ".repeat(8), lines.get(1),
                "the banner has no vote, and its own columns 73-80 are blanked all the same");
        assertSameLengths(text, result.text());
    }

    /** A directive line too short to carry a comment marker becomes blanks of its own length. */
    @Test
    void blanksAForeignLineTooShortToComment() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//JOB1     JOB  (ACCT),'X'",
                ")",
                "//STEP010  EXEC PGM=PGMA",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "short.jcl");

        assertEquals(" ", List.of(result.text().split("\n", -1)).get(1));
        assertEquals(List.of(2),
                result.directives().stream().map(f -> f.location().line()).toList());
        assertSameLengths(text, result.text());
    }

    /** A skeleton writes a dialog symbol in the name field, where no rule of the grammar reads it. */
    @Test
    void putsANameInPlaceOfASymbolWrittenInTheNameField() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//&ZUSER.J  JOB  (ACCT),'X'",
                "//STEP0&X   EXEC PGM=PGMA",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "skeleton.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        assertEquals(List.of("&ZUSER.", "&X"), markers.variables());
        String jobName = lines.get(0).substring(2, lines.get(0).indexOf("  JOB"));
        String stepName = lines.get(1).substring(2, lines.get(1).indexOf("   EXEC"));
        assertEquals("&ZUSER.J".length(), jobName.length(), jobName);
        assertEquals("STEP0&X".length(), stepName.length(), stepName);
        assertEquals("&ZUSER.J", markers.restore(jobName));
        assertEquals("STEP0&X", markers.restore(stepName));
        assertSameLengths(text, result.text());
    }

    /**
     * A symbol JCL leaves no room for: a name of eight characters and the dot that ends it make
     * nine, and the stand-in can only be eight. The name field is padded back out, so every
     * column after it stays where the author put it.
     */
    @Test
    void padsTheNameFieldOfASymbolTooLongToStandInFor() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = String.join("\n",
                "//&JOBNAME. JOB  (ACCT),'X'",
                "//STEP010   EXEC PGM=PGMA",
                "");
        SchedulerMarkers.Result result = apply(markers, text, "skeleton.jcl");

        List<String> lines = List.of(result.text().split("\n", -1));
        String jobName = lines.get(0).substring(2, lines.get(0).indexOf(' ', 2));
        assertEquals("&JOBNAME.", markers.restore(jobName));
        assertSameLengths(text, result.text());
    }

    /** A value the grammar would not read is stood in for without moving any other column. */
    @Test
    void standsInForAValueOfTheSameLength() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String value = "(DLI,CCP008,PSB001,7,0000,,0,,N,0,T,,,,,)";
        String stand = markers.salvage(value, "//STEP010  EXEC PGM=DFSRRC00,PARM=" + value);

        assertEquals(value.length(), stand.length());
        assertEquals(value, markers.restore(stand));
    }

    /** A value of three characters is stood in for and put back just as a long one is. */
    @Test
    void standsInForAShortValueAndStillPutsItBack() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String stand = markers.salvage("SHR", "//IN1      DD   DSN=A.B,DISP=SHR");

        assertEquals(3, stand.length());
        assertEquals("SHR", markers.restore(stand));
        assertEquals("SHR", markers.restore("DISP=" + stand).substring(5));
    }

    /** A stand-in is put back only where it stands on its own, never inside a longer name. */
    @Test
    void putsAStandInBackAsAWholeNameOnly() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String stand = markers.salvage("SHR", "//IN1      DD   DISP=SHR");

        assertEquals("SYKT." + stand + "PART", markers.restore("SYKT." + stand + "PART"),
                "the characters inside a longer name belong to that name");
        assertEquals("SYKT.SHR", markers.restore("SYKT." + stand));
    }

    /** The value put back may itself hold a scheduler token, which is put back after it. */
    @Test
    void putsBackASchedulerTokenInsideASalvagedValue() {
        SchedulerMarkers markers = new SchedulerMarkers();
        String text = "//IN1      DD   FROBNICATE=%%ODATE.FILE\n";
        SchedulerMarkers.Result result = apply(markers, text, "nested.jcl");
        String written = result.text().substring(result.text().indexOf('=') + 1).strip();
        String stand = markers.salvage(written, result.text());

        assertEquals("%%ODATE.FILE", markers.restore(stand));
    }

    private static String pad(String line) {
        return line + " ".repeat(72 - line.length());
    }
}
