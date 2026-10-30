package jp.cobolinsight.transpile;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.transpile.emit.Transpiler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A PERFORM … THRU over three paragraphs calls all three in written order, plain and in an UNTIL,
 * VARYING or TIMES loop: each paragraph's method returns at its end, so the range is called
 * method by method.
 */
class ThruLoopTranspileTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  THRULOOP.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-I         PIC 9(3) VALUE 0.",
            "       01  WS-EOF       PIC X VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM A-START THRU A-EXIT",
            "           PERFORM A-START THRU A-EXIT UNTIL WS-EOF = 'Y'",
            "           PERFORM A-START THRU A-EXIT",
            "               VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 3",
            "           PERFORM A-START THRU A-EXIT 2 TIMES",
            "           STOP RUN.",
            "       A-START.",
            "           MOVE 'Y' TO WS-EOF.",
            "       A-MID.",
            "           DISPLAY 'MID'.",
            "       A-EXIT.",
            "           EXIT.",
            "");

    private static String program(TargetLanguage language, String suffix) {
        String path = Path.of("THRULOOP.cbl").toAbsolutePath().toString();
        byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser().parse(
                new DecodedSource(path, SOURCE, bytes, new int[SOURCE.length()],
                        new EncodingInfo("UTF-8", 1.0, false, false)), List.of());
        CobolSemanticModel model = outcome.value().orElseThrow(() -> new AssertionError(
                "parse failed: " + outcome.failureFinding().orElse(null)));
        return Transpiler.transpile(model, SOURCE, language).files().stream()
                .filter(file -> file.fileName().endsWith(suffix)).findFirst().orElseThrow()
                .content();
    }

    /** The lines of the method that opens with {@code header}, up to the next method. */
    private static List<String> body(String program, String header, String next) {
        int start = program.indexOf(header);
        int end = program.indexOf(next, start + header.length());
        return program.substring(start + header.length(), end).lines().map(String::strip)
                .toList();
    }

    /** Four PERFORMs, each calling the three paragraphs in written order. */
    private static List<String> fourTimes(String start, String mid, String exit) {
        List<String> calls = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            calls.addAll(List.of(start, mid, exit));
        }
        return calls;
    }

    @Test
    void pythonCallsEveryParagraphOfTheRange() {
        String py = program(TargetLanguage.PYTHON, "_program.py");
        List<String> main = body(py, "def MAIN_PARA(self):", "\n    def ");

        assertEquals(fourTimes("self.A_START()", "self.A_MID()", "self.A_EXIT()"),
                main.stream().filter(line -> line.startsWith("self.A_")).toList(), py);
        assertEquals(3, main.stream().filter(line -> line.startsWith("while")
                || line.startsWith("for")).count(), py);
        assertFalse(py.contains("中間段落は省略"), py);
    }

    @Test
    void javaCallsEveryParagraphOfTheRange() {
        String java = program(TargetLanguage.JAVA, "Program.java");
        List<String> main = body(java, "void MAIN_PARA() {", "\n    void ");

        assertEquals(fourTimes("A_START();", "A_MID();", "A_EXIT();"),
                main.stream().filter(line -> line.startsWith("A_")).toList(), java);
        assertEquals(3, main.stream().filter(line -> line.startsWith("while")
                || line.startsWith("for")).count(), java);
        assertFalse(java.contains("中間段落は省略"), java);
    }

    /** The method of a paragraph returns at its end: A_START calls nothing of A-MID. */
    @Test
    void aParagraphMethodRunsItsOwnStatementsAlone() {
        String py = program(TargetLanguage.PYTHON, "_program.py");

        assertEquals(Collections.emptyList(), body(py, "def A_START(self):", "\n    def ").stream()
                .filter(line -> line.startsWith("self.A_") || line.contains("MID")).toList(),
                py);
    }
}
