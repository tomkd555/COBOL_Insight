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
        return capture(() -> new CommandLine(new Main()).execute(args));
    }

    /** picocli を通さず、組み立て済みのコマンドをそのまま動かす(差し替えた試験用の実装向け)。 */
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

    /**
     * 書き戻しの途中で失敗しても原本は元のバイト列のままであること。原本を直接開いて書くと、
     * ここで原本が切り詰められた中途半端な内容になる。
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
     * 書き戻しの後で失敗した場合は written:true と報告すること。原本を置き換えた後に
     * 「触れていない」と伝えると、利用者はもう残っていない元の内容を当てにする。
     */
    @Test
    void failureAfterTheReplacementIsReportedAsWritten() throws IOException {
        Path file = writeSource(PROGRAM, SJIS);
        String editedText = PROGRAM.replace("'日次処理'", "'月次処理'");
        Path edited = writeEdited(editedText);

        SaveCommand command = new SaveCommand() {
            @Override
            jp.cobolinsight.fix.ReparseResult verifyReparse(Path target, byte[] bytes,
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
     * COPY を持つ資産を保存する。コピー句の名前は試験ごとに変える。Che4z は解決の可否を
     * コピー句の名前で憶えるため、同じ名前を別の探索パスで2度引くと2度目が1度目の結果になる。
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
     * --copybook-path の指定が無い保存でも、COPY を持つ資産が再パースの誤りを伴わないこと。
     * 探索パスを空のまま再パースすると「Copybook not found」が必ず出て、編集と無関係な誤りを
     * 利用者へ見せる。
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

    /** コピー句を見つけられない探索パスでは誤りが出ること(上の試験が効いていることの裏)。 */
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
