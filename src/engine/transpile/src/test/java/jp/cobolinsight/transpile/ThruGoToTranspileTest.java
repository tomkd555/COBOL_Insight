package jp.cobolinsight.transpile;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;
import jp.cobolinsight.transpile.emit.Transpiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A THRU range a GO TO jumps inside: the range becomes one method, so the jump skips what COBOL
 * skips. A GO TO out of the range, and a THRU end ahead of its target, are stated in a comment.
 */
class ThruGoToTranspileTest {

    private static final String EARLY_EXIT = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  THRUGOTO.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-EOF       PIC X VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM A-START THRU A-EXIT",
            "           PERFORM A-START THRU A-EXIT UNTIL WS-EOF = 'Y'",
            "           PERFORM A-MID THRU A-START",
            "           STOP RUN.",
            "       A-START.",
            "           IF WS-EOF = 'Y'",
            "               GO TO A-EXIT",
            "           END-IF.",
            "       A-MID.",
            "           DISPLAY 'MID'.",
            "       A-EXIT.",
            "           EXIT.",
            "");

    /** The same range run by a VARYING loop and by a TIMES loop. */
    private static final String LOOPED = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  THRULOOPS.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-EOF       PIC X VALUE 'N'.",
            "       01  WS-I         PIC 9 VALUE 0.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM A-START THRU A-EXIT",
            "               VARYING WS-I FROM 1 BY 1 UNTIL WS-I > 3",
            "           PERFORM A-START THRU A-EXIT 2 TIMES",
            "           STOP RUN.",
            "       A-START.",
            "           IF WS-EOF = 'Y'",
            "               GO TO A-EXIT",
            "           END-IF.",
            "       A-MID.",
            "           DISPLAY 'MID'.",
            "       A-EXIT.",
            "           EXIT.",
            "");

    private static final String OUT_OF_RANGE = String.join("\n",
            "       IDENTIFICATION DIVISION.",
            "       PROGRAM-ID.  THRUOUT.",
            "       DATA DIVISION.",
            "       WORKING-STORAGE SECTION.",
            "       01  WS-EOF       PIC X VALUE 'N'.",
            "       PROCEDURE DIVISION.",
            "       MAIN-PARA.",
            "           PERFORM A-START THRU A-EXIT",
            "           STOP RUN.",
            "       A-START.",
            "           IF WS-EOF = 'Y'",
            "               GO TO OUT-PARA",
            "           END-IF.",
            "       A-EXIT.",
            "           EXIT.",
            "       OUT-PARA.",
            "           STOP RUN.",
            "");

    private static String program(String source, String name, TargetLanguage language,
            String suffix) {
        String path = Path.of(name + ".cbl").toAbsolutePath().toString();
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        ParseOutcome<CobolSemanticModel> outcome = new Che4zCobolParser().parse(
                new DecodedSource(path, source, bytes, new int[source.length()],
                        new EncodingInfo("UTF-8", 1.0, false, false)), List.of());
        CobolSemanticModel model = outcome.value().orElseThrow(() -> new AssertionError(
                "parse failed: " + outcome.failureFinding().orElse(null)));
        return Transpiler.transpile(model, source, language).files().stream()
                .filter(file -> file.fileName().endsWith(suffix)).findFirst().orElseThrow()
                .content();
    }

    /** The lines of the method that opens with {@code header}, up to the next method. */
    private static List<String> body(String program, String header, String next) {
        int start = program.indexOf(header);
        assertTrue(start >= 0, () -> "no method " + header + " in\n" + program);
        int end = program.indexOf(next, start + header.length());
        return program.substring(start + header.length(), end < 0 ? program.length() : end)
                .lines().filter(line -> !line.isBlank()).toList();
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    /**
     * Whether the line holding MID stands inside the block of the first {@code if} of the Python
     * method: below it and indented deeper than it, with no line between at the if's own indent.
     */
    private static boolean midInsidePythonIf(List<String> method) {
        int at = indexOf(method, line -> line.strip().startsWith("if "));
        int mid = indexOf(method, line -> line.contains("MID"));
        if (at < 0 || mid <= at) {
            return false;
        }
        for (int i = at + 1; i <= mid; i++) {
            if (indent(method.get(i)) <= indent(method.get(at))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the line holding MID stands between the braces of the first Java {@code if}. */
    private static boolean midInsideJavaIf(List<String> method) {
        int at = indexOf(method, line -> line.strip().startsWith("if "));
        int mid = indexOf(method, line -> line.contains("MID"));
        if (at < 0 || mid <= at) {
            return false;
        }
        int depth = 0;
        for (int i = at; i < mid; i++) {
            for (char c : method.get(i).toCharArray()) {
                depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
            if (depth <= 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(List<String> lines, java.util.function.Predicate<String> test) {
        for (int i = 0; i < lines.size(); i++) {
            if (test.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void pythonRunsTheRangeAsOneMethodWhoseJumpSkipsTheMiddle() {
        String py = program(EARLY_EXIT, "THRUGOTO", TargetLanguage.PYTHON, "_program.py");
        List<String> main = body(py, "def MAIN_PARA(self):", "\n    def ");

        assertEquals(2, main.stream().filter(line -> line.strip()
                .equals("self.A_START_THRU_A_EXIT()")).count(), py);
        List<String> range = body(py, "def A_START_THRU_A_EXIT(self):", "\n    def ");
        assertTrue(midInsidePythonIf(range),
                () -> "the DISPLAY of A-MID runs under the condition the GO TO skips:\n" + py);
        assertTrue(main.stream().anyMatch(line -> line.contains("対象の段落だけを呼出")),
                () -> "a THRU end ahead of its target is stated:\n" + py);
    }

    @Test
    void javaRunsTheRangeAsOneMethodAndCompiles(@TempDir Path dir) throws Exception {
        String java = program(EARLY_EXIT, "THRUGOTO", TargetLanguage.JAVA, "Program.java");
        List<String> main = body(java, "void MAIN_PARA() {", "\n    void ");

        assertEquals(2, main.stream().filter(line -> line.strip()
                .equals("A_START_THRU_A_EXIT();")).count(), java);
        List<String> range = body(java, "void A_START_THRU_A_EXIT() {", "\n    void ");
        assertTrue(midInsideJavaIf(range),
                () -> "the DISPLAY of A-MID runs under the condition the GO TO skips:\n" + java);
        compiles(dir, "THRUGOTOProgram.java", java);
    }

    /** A VARYING loop and a TIMES loop over the range call the one method of the range. */
    @Test
    void varyingAndTimesLoopsRunTheRangeAsOneMethod(@TempDir Path dir) throws Exception {
        String py = program(LOOPED, "THRULOOPS", TargetLanguage.PYTHON, "_program.py");
        assertEquals(2, body(py, "def MAIN_PARA(self):", "\n    def ").stream()
                .filter(line -> line.contains("self.A_START_THRU_A_EXIT()")).count(), py);
        assertTrue(midInsidePythonIf(body(py, "def A_START_THRU_A_EXIT(self):", "\n    def ")),
                py);

        String java = program(LOOPED, "THRULOOPS", TargetLanguage.JAVA, "Program.java");
        assertEquals(2, body(java, "void MAIN_PARA() {", "\n    void ").stream()
                .filter(line -> line.contains("A_START_THRU_A_EXIT()")).count(), java);
        assertTrue(midInsideJavaIf(body(java, "void A_START_THRU_A_EXIT() {", "\n    void ")),
                java);
        compiles(dir, "THRULOOPSProgram.java", java);
    }

    private static void compiles(Path dir, String fileName, String java) throws Exception {
        Path source = dir.resolve(fileName);
        Files.writeString(source, java, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, err, "-encoding", "UTF-8", "-d",
                dir.resolve("out").toString(), source.toString());
        assertEquals(0, rc, () -> err.toString(StandardCharsets.UTF_8) + "\n" + java);
    }

    @Test
    void aGoToOutOfTheRangeIsStatedInBothLanguages() {
        for (String[] target : new String[][] {{"PYTHON", "_program.py"},
                {"JAVA", "Program.java"}}) {
            String code = program(OUT_OF_RANGE, "THRUOUT", TargetLanguage.valueOf(target[0]),
                    target[1]);
            assertTrue(code.contains("THRU 範囲内の GO TO は再現していない"), code);
        }
    }
}
