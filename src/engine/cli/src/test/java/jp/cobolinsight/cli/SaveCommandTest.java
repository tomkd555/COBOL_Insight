package jp.cobolinsight.cli;

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
 * save サブコマンドの end-to-end 検証。画面で編集した全文を原本のコードページのまま原本へ書き戻し、
 * 再パース検証の結果を要約 JSON へ載せることを確かめる。
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

    /** 要約 JSON から数値の項目を取り出す。 */
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
        // 原本は windows-31j のまま。UTF-8 で書き戻していないこと。
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

    @Test
    void codepageRecordedByScanIsUsedWhenTheProjectFileIsGiven() throws IOException {
        // 本文は ASCII だけで、自動判別では UTF-8 になる。scan へ windows-31j を手動指定して
        // プロジェクトファイルへ記録させ、save がその記録を引くことを日本語の追記で確かめる。
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
