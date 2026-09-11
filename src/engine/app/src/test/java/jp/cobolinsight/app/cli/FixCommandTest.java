package jp.cobolinsight.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the fix subcommand's picocli wiring, output in a relative layout, and
 * the reparse-verification gate, against samples/. The originals (samples/) are never modified.
 */
class FixCommandTest {

    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    private record Run(int exitCode, String stdout) {
    }

    private Run execute(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = new CommandLine(new Main()).execute(args);
            System.out.flush();
            return new Run(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void writesFixedSourcesPreservingLayoutAndReparses() throws IOException {
        Path out = tempDir.resolve("fixed");
        byte[] originalBefore = Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK007.cbl"));

        Run run = execute("fix", SAMPLES.toString(), "--out", out.toString());

        assertEquals(0, run.exitCode(),
                "全ての修正後ソースが再パースできれば成功(0)。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"reparseFailures\":0"), run.stdout());

        // Must write out while preserving the relative path layout (cobol/).
        Path syk001 = out.resolve("cobol").resolve("SYK001.cbl");
        Path syk007 = out.resolve("cobol").resolve("SYK007.cbl");
        assertTrue(Files.exists(syk001), "出力が cobol/ 構成を保つこと");
        String syk001Text = Files.readString(syk001, StandardCharsets.UTF_8);
        assertTrue(syk001Text.contains("IF WS-ORDIN-STATUS NOT = '00'"),
                "FILE STATUS 検査が挿入されること");
        String syk007Text = Files.readString(syk007, StandardCharsets.UTF_8);
        assertTrue(syk007Text.contains("END-COMPUTE"), "ON SIZE ERROR+END-COMPUTE が入ること");
        assertTrue(syk007Text.contains("IF SQLCODE NOT = 0"), "SQLCODE 検査が入ること");

        // The original must not be modified.
        assertArrayEquals(originalBefore,
                Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK007.cbl")),
                "fix は原本を変更しないこと");
    }

    @Test
    void preservesFixedFormatColumns() throws IOException {
        Path out = tempDir.resolve("cols");
        execute("fix", SAMPLES.toString(), "--out", out.toString());
        String syk001 = Files.readString(out.resolve("cobol").resolve("SYK001.cbl"),
                StandardCharsets.UTF_8);
        // The inserted check statement must start at the B-area origin (column 12), with the sequence-number field (columns 1-6) left blank.
        String inserted = syk001.lines()
                .filter(line -> line.contains("IF WS-ORDIN-STATUS NOT = '00'"))
                .findFirst().orElseThrow();
        assertTrue(inserted.startsWith("           IF"), "B領域12桁目起点であること: [" + inserted + "]");
    }
}
