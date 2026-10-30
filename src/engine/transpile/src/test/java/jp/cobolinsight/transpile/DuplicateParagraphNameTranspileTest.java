package jp.cobolinsight.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.EncodingInfo;
import jp.cobolinsight.core.spi.ParseOutcome;
import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
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
 * Two sections each hold a paragraph COMMON-EXIT. Each becomes a method of its own, qualified by
 * its section, and every PERFORM, PERFORM THRU and GO TO reaches the one ProcedureNames resolves:
 * an unqualified name the one of its own section, a qualified name the one of the named section.
 */
class DuplicateParagraphNameTranspileTest {

    private static final String SOURCE = String.join("\n",
            "       IDENTIFICATION DIVISION.",                         // 1
            "       PROGRAM-ID.  DUPNAME.",                            // 2
            "       DATA DIVISION.",                                   // 3
            "       WORKING-STORAGE SECTION.",                         // 4
            "       01  WS-X         PIC 9 VALUE 0.",                  // 5
            "       PROCEDURE DIVISION.",                              // 6
            "       A-SECT SECTION.",                                  // 7
            "       A-START.",                                         // 8
            "           PERFORM COMMON-EXIT",                          // 9
            "           PERFORM COMMON-EXIT IN B-SECT",                // 10
            "           PERFORM A-START THRU COMMON-EXIT",             // 11
            "           STOP RUN.",                                    // 12
            "       COMMON-EXIT.",                                     // 13
            "           DISPLAY 'A-EXIT'",                             // 14
            "           GOBACK.",                                      // 15
            "       B-SECT SECTION.",                                  // 16
            "       B-START.",                                         // 17
            "           PERFORM COMMON-EXIT",                          // 18
            "           PERFORM COMMON-EXIT OF A-SECT",                // 19
            "           PERFORM B-START THRU COMMON-EXIT.",            // 20
            "       COMMON-EXIT.",                                     // 21
            "           DISPLAY 'B-EXIT'",                             // 22
            "           GO TO COMMON-EXIT IN A-SECT.",                 // 23
            "");

    private static TranspileResult transpile(TargetLanguage language) {
        String path = Path.of("DUPNAME.cbl").toAbsolutePath().toString();
        byte[] bytes = SOURCE.getBytes(StandardCharsets.UTF_8);
        DecodedSource source = new DecodedSource(path, SOURCE, bytes, new int[SOURCE.length()],
                new EncodingInfo("UTF-8", 1.0, false, false));
        ParseOutcome<CobolSemanticModel> outcome =
                new Che4zCobolParser().parse(source, List.of());
        CobolSemanticModel model = outcome.value().orElseThrow(() -> new AssertionError(
                "parse failed: " + outcome.failureFinding().orElse(null)));
        return Transpiler.transpile(model, SOURCE, language);
    }

    private static GeneratedFile program(TranspileResult result, String suffix) {
        return result.files().stream().filter(f -> f.fileName().endsWith(suffix)).findFirst()
                .orElseThrow(() -> new AssertionError("no program file: " + suffix));
    }

    /** The lines of the method that opens with {@code header}, up to the next method. */
    private static List<String> body(String program, String header, String nextMethod) {
        int start = program.indexOf(header);
        assertTrue(start >= 0, () -> "no method " + header + " in\n" + program);
        int end = program.indexOf(nextMethod, start + header.length());
        return program.substring(start + header.length(), end < 0 ? program.length() : end)
                .lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")
                        && !line.startsWith("//") && !line.equals("}"))
                .toList();
    }

    @Test
    void pythonCallsTheMethodOfTheParagraphEachPerformResolvesTo() {
        String py = program(transpile(TargetLanguage.PYTHON), "_program.py").content();

        for (String method : List.of("A_SECT", "A_START", "COMMON_EXIT_IN_A_SECT", "B_SECT",
                "B_START", "COMMON_EXIT_IN_B_SECT")) {
            assertEquals(1, py.split("def " + method + "\\(self\\):", -1).length - 1,
                    () -> method + " in\n" + py);
        }
        assertEquals(List.of("self.COMMON_EXIT_IN_A_SECT()", "self.COMMON_EXIT_IN_B_SECT()",
                        "self.A_START()", "self.COMMON_EXIT_IN_A_SECT()"),
                body(py, "def A_START(self):", "\n    def ").stream()
                        .filter(line -> line.startsWith("self.")).toList(), py);
        assertEquals(List.of("self.COMMON_EXIT_IN_B_SECT()", "self.COMMON_EXIT_IN_A_SECT()",
                        "self.B_START()", "self.COMMON_EXIT_IN_B_SECT()"),
                body(py, "def B_START(self):", "\n    def ").stream()
                        .filter(line -> line.startsWith("self.")).toList(), py);
    }

    @Test
    void javaDeclaresEachMethodOnceAndCompiles(@TempDir Path dir) throws Exception {
        GeneratedFile file = program(transpile(TargetLanguage.JAVA), "Program.java");
        String java = file.content();

        assertEquals(List.of("COMMON_EXIT_IN_A_SECT();", "COMMON_EXIT_IN_B_SECT();",
                        "A_START();", "COMMON_EXIT_IN_A_SECT();"),
                body(java, "void A_START() {", "\n    void ").stream()
                        .filter(line -> line.endsWith("();")).toList(), java);
        assertEquals(List.of("COMMON_EXIT_IN_B_SECT();", "COMMON_EXIT_IN_A_SECT();",
                        "B_START();", "COMMON_EXIT_IN_B_SECT();"),
                body(java, "void B_START() {", "\n    void ").stream()
                        .filter(line -> line.endsWith("();")).toList(), java);

        Path source = dir.resolve(file.fileName());
        Files.writeString(source, java, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, err, "-encoding", "UTF-8", "-d",
                dir.resolve("out").toString(), source.toString());
        assertEquals(0, rc, () -> err.toString(StandardCharsets.UTF_8) + "\n" + java);
    }

    /**
     * B-SECT's COMMON-EXIT goes to A-SECT's: the copy of A-SECT's DISPLAY in B-SECT's method is
     * the GO TO duplicate, and is noted as one though both paragraphs carry the same name.
     */
    @Test
    void aGoToReachesTheQualifiedParagraphAndItsCopyIsNoted() {
        TranspileResult py = transpile(TargetLanguage.PYTHON);
        String program = program(py, "_program.py").content();

        List<String> exitOfB = body(program, "def COMMON_EXIT_IN_B_SECT(self):", "\n    def ");
        assertTrue(exitOfB.contains("print(\"B-EXIT\")"), program);
        assertTrue(exitOfB.contains("print(\"A-EXIT\")"), program);
        List<LineMappingEntry> display = py.lineMap().stream()
                .filter(e -> e.cobolLines().startLine() == 14)
                .filter(e -> e.generatedFile().endsWith("_program.py")).toList();
        assertEquals(2, display.size(), display::toString);
        assertEquals(1, display.stream().filter(e -> e.note().contains("複製")).count(),
                display::toString);
    }
}
