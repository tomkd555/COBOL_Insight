package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies inline expansion of COPY statements: that the original COPY statement's line number,
 * the copybook name, and each expanded line (its originating line within the copybook and the
 * text after REPLACING is applied) all line up.
 */
class CopyInlineExpansionTest {

    private static CopyInlineExpansion expansionOf(CobolSemanticModel model, String copybookName) {
        return model.copyInlineExpansions().stream()
                .filter(e -> e.copybookName().equals(copybookName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(copybookName + " の展開が無い: "
                        + model.copyInlineExpansions()));
    }

    private static String textAt(CopyInlineExpansion expansion, int copybookLine) {
        return expansion.lines().stream()
                .filter(line -> line.copybookLine() == copybookLine)
                .map(ExpandedCopyLine::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        copybookLine + " 行目の展開行が無い: " + expansion.lines()));
    }

    @Test
    void syk001PointsAtItsCopyStatementLineAndCarriesReplacedText() {
        CopyInlineExpansion expansion = expansionOf(TestSources.model("SYK001.cbl"), "SYKCPY1");

        assertEquals(32, expansion.copyStatementLine(), "原本 SYK001.cbl の COPY 文は32行目");
        assertTrue(expansion.copybookPath().endsWith("SYKCPY1.cpy"), expansion.copybookPath());
        assertTrue(textAt(expansion, 7).contains("01  ORD1-受注レコード."),
                "LEADING ==SYK1== BY ==ORD1== の置換後テキストであること: " + textAt(expansion, 7));
        assertFalse(expansion.lines().stream().anyMatch(line -> line.text().contains("SYK1-")),
                "置換前の SYK1- が残らないこと");
    }

    @Test
    void expandedLinesAreOrderedAndCoverTheCopybook() {
        CopyInlineExpansion expansion = expansionOf(TestSources.model("SYK001.cbl"), "SYKCPY1");
        List<ExpandedCopyLine> lines = expansion.lines();

        assertEquals(1, lines.get(0).copybookLine(), "コピー句の先頭行から始まること");
        for (int i = 1; i < lines.size(); i++) {
            assertTrue(lines.get(i).copybookLine() > lines.get(i - 1).copybookLine(),
                    "由来行が昇順であること: " + lines);
        }
    }

    @Test
    void syk002HasOneExpansionPerCopyStatement() {
        CobolSemanticModel model = TestSources.model("SYK002.cbl");
        assertEquals(31, expansionOf(model, "SYKCPY1").copyStatementLine());
        assertEquals(35, expansionOf(model, "SYKCPY2").copyStatementLine());
        assertTrue(textAt(expansionOf(model, "SYKCPY1"), 7).contains("IN1-受注レコード"));
        assertTrue(textAt(expansionOf(model, "SYKCPY2"), 7).contains("SYK2-受注マスタレコード"),
                "置換指定の無いコピー句はそのまま展開されること");
    }

    @Test
    void implicitCopybooksAreExcluded() {
        CobolSemanticModel model = TestSources.model("SYK006.cbl");
        assertEquals(40, expansionOf(model, "SYKCPY3").copyStatementLine());
        assertTrue(model.copyInlineExpansions().stream()
                        .noneMatch(e -> e.copybookName().equals("SQLCA")),
                "暗黙コード(SQLCA)は展開対象から除くこと: " + model.copyInlineExpansions());
    }
}
