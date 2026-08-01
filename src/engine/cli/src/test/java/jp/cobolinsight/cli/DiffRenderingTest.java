package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** unified diff の ANSI 着色・自己完結 HTML 整形({@link DiffRendering})の検証。 */
class DiffRenderingTest {

    private static final String RESET = "[0m";
    private static final String GREEN = "[32m";
    private static final String RED = "[31m";
    private static final String CYAN = "[36m";
    private static final String BOLD = "[1m";

    @Test
    void ansiColorsEachLineByPrefix() {
        List<String> colored = DiffRendering.ansi(List.of(
                "--- a/x.cbl", "+++ b/x.cbl", "@@ -1 +1 @@", "+added", "-removed", " context"));

        assertTrue(colored.get(0).startsWith(BOLD) && colored.get(0).endsWith(RESET), colored.get(0));
        assertTrue(colored.get(1).startsWith(BOLD), colored.get(1));
        assertTrue(colored.get(2).startsWith(CYAN), colored.get(2));
        assertTrue(colored.get(3).equals(GREEN + "+added" + RESET), colored.get(3));
        assertTrue(colored.get(4).equals(RED + "-removed" + RESET), colored.get(4));
        // 文脈行は着色しないが RESET は付す(前行の色を持ち越さない)。
        assertTrue(colored.get(5).equals(" context" + RESET), colored.get(5));
    }

    @Test
    void htmlClassesLinesAndEscapes() {
        String html = DiffRendering.html(List.of(new DiffRendering.FileDiff("cobol/x.cbl",
                List.of("+IF A < B", "-OLD", "@@ -1 +1 @@", " ctx"))));

        assertTrue(html.contains("<span class=\"add\">+IF A &lt; B</span>"), html);
        assertTrue(html.contains("<span class=\"del\">-OLD</span>"), html);
        assertTrue(html.contains("<span class=\"hunk\">@@ -1 +1 @@</span>"), html);
        assertTrue(html.contains("<h2>cobol/x.cbl</h2>"), html);
        // 外部資産に依存しない自己完結 HTML であること。
        assertTrue(!html.contains("http://") && !html.contains("<script"), html);
    }
}
