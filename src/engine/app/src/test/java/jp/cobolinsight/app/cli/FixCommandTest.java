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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fix サブコマンド(preview/apply)の picocli 配線・差分表示・相対構成での出力・再パース検証ゲートを、
 * samples/ を対象に end-to-end で検証する。原本(samples/)は変更しない。
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
    void previewShowsUnifiedDiffForKnownFixTargets() {
        Run run = execute("fix", "preview", SAMPLES.toString());

        assertEquals(0, run.exitCode(), "解析に error が無ければ成功(0)。stdout=" + run.stdout());
        String out = run.stdout();
        // R017(FILE STATUS 検査)・R004(ON SIZE ERROR)・R018(SQLCODE 検査)の挿入が追加行として出ること。
        assertTrue(out.contains("+           IF WS-ORDIN-STATUS NOT = '00'"), out);
        assertTrue(out.contains("+           ON SIZE ERROR DISPLAY 'SIZE ERROR: WS-引当率' END-COMPUTE"),
                out);
        assertTrue(out.contains("+           IF SQLCODE NOT = 0 DISPLAY 'SQL ERROR: ' SQLCODE END-IF."),
                out);
        // unified diff のハンク見出しと、対象ファイルがサマリへ載ること。
        assertTrue(out.contains("@@"), out);
        assertTrue(out.contains("\"cobol/SYK001.cbl\""), out);
        assertTrue(out.contains("\"cobol/SYK007.cbl\""), out);
    }

    @Test
    void previewLeavesOriginalSamplesUnchanged() throws IOException {
        byte[] before = Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK001.cbl"));
        execute("fix", "preview", SAMPLES.toString());
        byte[] after = Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK001.cbl"));
        assertArrayEquals(before, after, "preview は原本を変更しないこと");
    }

    @Test
    void applyWritesFixedSourcesPreservingLayoutAndReparses() throws IOException {
        Path out = tempDir.resolve("fixed");
        byte[] originalBefore = Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK007.cbl"));

        Run run = execute("fix", "apply", SAMPLES.toString(), "--out", out.toString());

        assertEquals(0, run.exitCode(),
                "全ての修正後ソースが再パースできれば成功(0)。stdout=" + run.stdout());
        assertTrue(run.stdout().contains("\"reparseFailures\":0"), run.stdout());

        // 相対パス構成(cobol/)を保って書き出すこと。
        Path syk001 = out.resolve("cobol").resolve("SYK001.cbl");
        Path syk007 = out.resolve("cobol").resolve("SYK007.cbl");
        assertTrue(Files.exists(syk001), "出力が cobol/ 構成を保つこと");
        String syk001Text = Files.readString(syk001, StandardCharsets.UTF_8);
        assertTrue(syk001Text.contains("IF WS-ORDIN-STATUS NOT = '00'"),
                "FILE STATUS 検査が挿入されること");
        String syk007Text = Files.readString(syk007, StandardCharsets.UTF_8);
        assertTrue(syk007Text.contains("END-COMPUTE"), "ON SIZE ERROR+END-COMPUTE が入ること");
        assertTrue(syk007Text.contains("IF SQLCODE NOT = 0"), "SQLCODE 検査が入ること");

        // 原本は変更しないこと。
        assertArrayEquals(originalBefore,
                Files.readAllBytes(SAMPLES.resolve("cobol").resolve("SYK007.cbl")),
                "apply は原本を変更しないこと");
    }

    @Test
    void applyPreservesFixedFormatColumns() throws IOException {
        Path out = tempDir.resolve("cols");
        execute("fix", "apply", SAMPLES.toString(), "--out", out.toString());
        String syk001 = Files.readString(out.resolve("cobol").resolve("SYK001.cbl"),
                StandardCharsets.UTF_8);
        // 挿入した検査文は B領域起点(12桁目)から始まり、一連番号欄(1-6桁)は空白であること。
        String inserted = syk001.lines()
                .filter(line -> line.contains("IF WS-ORDIN-STATUS NOT = '00'"))
                .findFirst().orElseThrow();
        assertTrue(inserted.startsWith("           IF"), "B領域12桁目起点であること: [" + inserted + "]");
    }

    @Test
    void previewHtmlIsSelfContained() throws IOException {
        Path html = tempDir.resolve("diff.html");
        Run run = execute("fix", "preview", SAMPLES.toString(), "--html", html.toString());

        assertEquals(0, run.exitCode());
        assertTrue(Files.exists(html), "HTML が書き出されること");
        String content = Files.readString(html, StandardCharsets.UTF_8);
        assertTrue(content.contains("<style"), "インライン CSS を持つこと");
        assertTrue(content.contains("cobol/SYK001.cbl"), "対象ファイルの差分を含むこと");
        assertTrue(content.contains("IF SQLCODE NOT = 0"), "挿入内容を含むこと");
        // 外部資産へ依存しない自己完結 HTML であること。
        assertFalse(content.contains("http://"), content);
        assertFalse(content.contains("https://"), content);
        assertFalse(content.contains("<script"), content);
    }
}
