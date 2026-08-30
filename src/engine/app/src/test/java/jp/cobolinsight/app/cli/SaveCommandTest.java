package jp.cobolinsight.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end verification of the save subcommand. Confirms that the full text edited on screen is
 * written back to the original file in the original codepage, and that the reparse verification
 * result is included in the summary JSON.
 */
class SaveCommandTest {

    private static final Charset SJIS = Charset.forName("windows-31j");

    private static final String DISPLAY_LINE = "           DISPLAY '日次処理'.";

    private static final String PROGRAM =
            "       IDENTIFICATION DIVISION.\n"
                    + "       PROGRAM-ID. SAVECHK.\n"
                    + "       PROCEDURE DIVISION.\n"
                    + "       0000-MAIN.\n"
                    + DISPLAY_LINE + "\n"
                    + "           STOP RUN.\n";

    @TempDir
    Path tempDir;

    private record Run(int exitCode, String stdout) {
    }

    private Run execute(String... args) {
        return capture(() -> new CommandLine(new Main()).execute(args));
    }

    /** Runs a pre-built command directly, bypassing picocli (for a substituted test implementation). */
    private Run execute(SaveCommand command) {
        return capture(command::call);
    }

    private Run capture(java.util.function.Supplier<Integer> body) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int exitCode = body.get();
            System.out.flush();
            return new Run(exitCode, buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(original);
        }
    }

    private Path writeSource(String text, Charset charset) throws IOException {
        Path file = tempDir.resolve("SAVECHK.cbl");
        Files.write(file, text.getBytes(charset));
        return file;
    }

    private Path writeEdited(String text) throws IOException {
        Path edited = tempDir.resolve("edited.txt");
        Files.write(edited, text.getBytes(StandardCharsets.UTF_8));
        return edited;
    }

    /** Extracts a numeric field from the summary JSON. */
    private static int intField(String summary, String name) {
        String key = "\"" + name + "\":";
        int at = summary.indexOf(key) + key.length();
        return Integer.parseInt(summary.substring(at, summary.indexOf(',', at)));
    }

    @Test
    void writesEditedTextBackInTheOriginalShiftJisEncoding() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        String editedText = PROGRAM.replace("'日次処理'", "'月次処理カナ'");
        Path edited = writeEdited(editedText);

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString());

        assertEquals(0, run.exitCode(), "再パースできる編集は成功(0)。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"written\":true"), run.stdout());
        assertTrue(run.stdout().contains("\"reparseErrors\":[]"), run.stdout());
        // The original stays in windows-31j. Confirms it was not written back in UTF-8.
        assertArrayEquals(editedText.getBytes(SJIS), Files.readAllBytes(file));
        assertEquals(5, intField(run.stdout(), "changedLineFrom"), run.stdout());
        assertEquals(5, intField(run.stdout(), "changedLineTo"), run.stdout());
    }

    @Test
    void identicalContentIsReportedAsNoOpAndLeavesTheFileUntouched() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        byte[] before = Files.readAllBytes(file);
        Path edited = writeEdited(PROGRAM);

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString());

        assertEquals(0, run.exitCode(), run.stdout());
        assertTrue(run.stdout().contains("\"written\":false"), run.stdout());
        assertArrayEquals(before, Files.readAllBytes(file), "無編集の保存は原本へ触れないこと");
    }

    @Test
    void unmappableCharacterFailsWithoutWritingAnything() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        byte[] before = Files.readAllBytes(file);
        Path edited = writeEdited(PROGRAM.replace("'日次処理'", "'日次処理🙂'"));

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString());

        assertEquals(2, run.exitCode(), "符号化できない文字は失敗(2)。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"written\":false"), run.stdout());
        assertTrue(run.stdout().contains("windows-31j"), run.stdout());
        assertArrayEquals(before, Files.readAllBytes(file), "失敗した保存は原本へ触れないこと");
    }

    @Test
    void reparseFailureStillWritesTheFileAndReportsTheProblem() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        String editedText = PROGRAM.replace(DISPLAY_LINE, "           MOVE TO .");
        Path edited = writeEdited(editedText);

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString());

        assertEquals(1, run.exitCode(),
                "書き戻しはできたが再パースが通らない場合は1。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"written\":true"), run.stdout());
        assertTrue(run.stdout().contains("\"message\":"), run.stdout());
        assertArrayEquals(editedText.getBytes(SJIS), Files.readAllBytes(file),
                "作業途中の保存を妨げないため、再パース失敗でも書き戻しは取り消さないこと");
    }

    /**
     * The original must stay byte-identical even if the write-back fails partway through. Opening
     * and writing the original directly would leave it truncated with half-finished content here.
     */
    @Test
    void failedWriteLeavesTheOriginalByteIdentical() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        byte[] before = Files.readAllBytes(file);
        Path edited = writeEdited(PROGRAM.replace("'日次処理'", "'月次処理'"));

        SaveCommand command = new SaveCommand() {
            @Override
            void writeTemp(Path temp, byte[] bytes) throws IOException {
                throw new IOException("書き出しに失敗した");
            }
        };
        command.file = file;
        command.editedFile = edited;
        Run run = execute(command);

        assertEquals(2, run.exitCode(), run.stdout());
        assertTrue(run.stdout().contains("\"written\":false"), run.stdout());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /**
     * A failure after the write-back must be reported as written:true. Telling the user "untouched"
     * after the original has already been replaced would make them rely on original content that no longer exists.
     */
    @Test
    void failureAfterTheReplacementIsReportedAsWritten() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        String editedText = PROGRAM.replace("'日次処理'", "'月次処理'");
        Path edited = writeEdited(editedText);

        SaveCommand command = new SaveCommand() {
            @Override
            jp.cobolinsight.core.fix.ReparseResult verifyReparse(Path target, byte[] bytes,
                    String charsetName) {
                throw new IllegalStateException("再パース検証が落ちた");
            }
        };
        command.file = file;
        command.editedFile = edited;
        Run run = execute(command);

        assertEquals(2, run.exitCode(), run.stdout());
        assertTrue(run.stdout().contains("\"written\":true"), run.stdout());
        assertTrue(run.stdout().contains("再パース検証が落ちた"), run.stdout());
        assertArrayEquals(editedText.getBytes(SJIS), Files.readAllBytes(file),
                "検証で落ちても書き戻し自体は済んでいること");
    }

    /**
     * Saves an asset that has a COPY. The copybook name is varied per test. Che4z remembers whether
     * resolution succeeded by copybook name, so looking up the same name twice with different search
     * paths would make the second lookup return the first lookup's result.
     */
    private Path writeCopyUsingSource(Path assets, String copybookName) throws IOException {
        Files.createDirectories(assets.resolve("copy"));
        Path file = assets.resolve(copybookName + "P.cbl");
        Files.writeString(file, String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  " + copybookName + "P.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       COPY " + copybookName + ".",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           DISPLAY WS-NAME.",
                "           STOP RUN.",
                ""), StandardCharsets.UTF_8);
        Files.writeString(assets.resolve("copy/" + copybookName + ".cpy"), String.join("\n",
                "       01  WS-REC.",
                "           05  WS-NAME    PIC X(10).",
                ""), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * An asset with a COPY must not incur a reparse error even when saved without an explicit
     * --copybook-path. Reparsing with an empty search path always produces "Copybook not found",
     * showing the user an error unrelated to their edit.
     */
    @Test
    void copybooksAreFoundWithoutAnExplicitSearchPath() throws IOException {
        Path assets = tempDir.resolve("assets");
        Path file = writeCopyUsingSource(assets, "SVCPBA");
        Path edited = writeEdited(Files.readString(file, StandardCharsets.UTF_8)
                .replace("DISPLAY WS-NAME", "DISPLAY WS-REC"));

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString());

        assertEquals(0, run.exitCode(), "COPY は既定の探索で解決すること。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"reparseErrors\":[]"), run.stdout());
    }

    /** Confirms an error occurs with a search path that cannot find the copybook (the flip side proving the test above is meaningful). */
    @Test
    void unresolvedCopybookIsReportedAsAReparseError() throws IOException {
        Path assets = tempDir.resolve("blind");
        Path file = writeCopyUsingSource(assets, "SVCPBB");
        Path empty = tempDir.resolve("empty");
        Files.createDirectories(empty);
        Path edited = writeEdited(Files.readString(file, StandardCharsets.UTF_8)
                .replace("DISPLAY WS-NAME", "DISPLAY WS-REC"));

        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString(),
                "--copybook-path", empty.toString());

        assertEquals(1, run.exitCode(), run.stdout());
        assertTrue(run.stdout().contains("Copybook not found"), run.stdout());
    }

    @Test
    void codepageRecordedByScanIsUsedWhenTheProjectFileIsGiven() throws IOException {
        // The body is ASCII-only, so automatic detection would resolve it as UTF-8. Manually
        // specify windows-31j to scan so it gets recorded in the project file, and confirm save
        // picks up that record by appending Japanese text.
        String ascii = PROGRAM.replace("'日次処理'", "'DAILY'");
        Path file = writeSource(ascii, StandardCharsets.US_ASCII);
        Path db = tempDir.resolve("project.db");
        execute("scan", tempDir.toString(), "--db", db.toString(),
                "--codepage", "SAVECHK.cbl=windows-31j");
        assertTrue(Files.exists(db), "scan がプロジェクトファイルを作ること");

        String editedText = ascii.replace("'DAILY'", "'日次'");
        Path edited = writeEdited(editedText);
        Run run = execute("save", "--file", file.toString(), "--edited", edited.toString(),
                "--db", db.toString());

        assertEquals(0, run.exitCode(), run.stdout());
        assertArrayEquals(editedText.getBytes(SJIS), Files.readAllBytes(file),
                "記録されたコードページ(windows-31j)で書き戻すこと");
    }
}
