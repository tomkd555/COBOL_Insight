package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.SimpleStatement;
import jp.cobolinsight.core.source.CopyInlineExpansion;
import jp.cobolinsight.core.source.ExpandedCopyLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    /**
     * OUTER copies INNER between its own lines. Each COPY statement of the program gets the whole
     * of what it brings in: OUTER's lines with INNER's at their place, marked with INNER's path.
     */
    @Test
    void anExpansionHoldsTheLinesANestedCopyBringsInAndTheLinesAfterIt(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("OUTER.cpy"), String.join("\n",
                "           CALL 'OUTERPGM'.",
                "           COPY INNER.",
                "           PERFORM LOG-ERROR.", ""));
        Files.writeString(dir.resolve("INNER.cpy"), String.join("\n",
                "           CALL 'INNERPGM'.",
                "           DISPLAY 'IN2'.", ""));
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  NESTPGM.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COPY OUTER.",
                "           COPY INNER.",
                "           COPY OUTER.",
                "           STOP RUN.",
                "       LOG-ERROR.",
                "           EXIT.",
                "");
        CobolSemanticModel model = new Che4zCobolParser()
                .parse(TestSources.fromText(dir.resolve("NESTPGM.cbl").toString(), source),
                        List.of(dir))
                .value().orElseThrow();
        String inner = dir.resolve("INNER.cpy").toString();
        List<ExpandedCopyLine> outerLines = List.of(
                new ExpandedCopyLine(1, "           CALL 'OUTERPGM'."),
                new ExpandedCopyLine(2, ""),
                new ExpandedCopyLine(1, "           CALL 'INNERPGM'.", Optional.of(inner)),
                new ExpandedCopyLine(2, "           DISPLAY 'IN2'.", Optional.of(inner)),
                new ExpandedCopyLine(3, "           PERFORM LOG-ERROR."));

        assertEquals(List.of(5, 6, 7), model.copyInlineExpansions().stream()
                .map(CopyInlineExpansion::copyStatementLine).toList());
        assertEquals(List.of("OUTER", "INNER", "OUTER"), model.copyInlineExpansions().stream()
                .map(CopyInlineExpansion::copybookName).toList());
        assertEquals(outerLines, model.copyInlineExpansions().get(0).lines());
        assertEquals(List.of(new ExpandedCopyLine(1, "           CALL 'INNERPGM'."),
                        new ExpandedCopyLine(2, "           DISPLAY 'IN2'.")),
                model.copyInlineExpansions().get(1).lines());
        assertEquals(outerLines, model.copyInlineExpansions().get(2).lines());
    }

    /** The texts of the procedure division's simple statements, in source order. */
    private static List<String> statementTexts(CobolSemanticModel model) {
        return model.procedures().stream().flatMap(p -> p.statements().stream())
                .map(s -> s.range().start().file().replaceAll(".*[\\\\/]", "") + " "
                        + ((SimpleStatement) s).text())
                .toList();
    }

    /** Two COPY statements on one program line both expand, each standing on that line. */
    @Test
    void twoCopyStatementsOnOneProgramLineBothExpand(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("FIRST.cpy"), "           DISPLAY 'FIRST'.\n");
        Files.writeString(dir.resolve("SECOND.cpy"), "           DISPLAY 'SECOND'.\n");
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  TWOCOPY.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COPY FIRST. COPY SECOND. DISPLAY 'AFTER'.",
                "           STOP RUN.",
                "");
        CobolSemanticModel model = new Che4zCobolParser()
                .parse(TestSources.fromText(dir.resolve("TWOCOPY.cbl").toString(), source),
                        List.of(dir))
                .value().orElseThrow();

        assertEquals(List.of(
                        new CopyInlineExpansion(5, "FIRST", dir.resolve("FIRST.cpy").toString(),
                                List.of(new ExpandedCopyLine(1, "           DISPLAY 'FIRST'."))),
                        new CopyInlineExpansion(5, "SECOND", dir.resolve("SECOND.cpy").toString(),
                                List.of(new ExpandedCopyLine(1,
                                        "           DISPLAY 'SECOND'.")))),
                model.copyInlineExpansions());
        assertEquals(List.of("FIRST.cpy DISPLAY 'FIRST'", "SECOND.cpy DISPLAY 'SECOND'",
                        "TWOCOPY.cbl DISPLAY 'AFTER'", "TWOCOPY.cbl STOP RUN"),
                statementTexts(model));
    }

    private static CobolSemanticModel midLineModel(Path dir, String procedureLine)
            throws Exception {
        Files.writeString(dir.resolve("FIRST.cpy"), "           DISPLAY 'FIRST'.\n");
        Files.writeString(dir.resolve("SECOND.cpy"), "           DISPLAY 'SECOND'.\n");
        Files.writeString(dir.resolve("THIRD.cpy"),
                "           DISPLAY 'THIRD'.\n           DISPLAY 'T2'.\n");
        Files.writeString(dir.resolve("OUTER.cpy"), "           DISPLAY 'O' COPY FIRST.\n");
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  MIDCOPY.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                procedureLine,
                "           STOP RUN.",
                "");
        return new Che4zCobolParser()
                .parse(TestSources.fromText(dir.resolve("MIDCOPY.cbl").toString(), source),
                        List.of(dir))
                .value().orElseThrow();
    }

    private static CopyInlineExpansion expansion(Path dir, String name,
            ExpandedCopyLine... lines) {
        return new CopyInlineExpansion(5, name, dir.resolve(name + ".cpy").toString(),
                List.of(lines));
    }

    /** A COPY after a statement on its line expands at that line, as one starting the line. */
    @Test
    void aCopyAfterAStatementExpandsAtItsLine(@TempDir Path dir) throws Exception {
        CobolSemanticModel model = midLineModel(dir, "           DISPLAY 'A' COPY FIRST.");

        assertEquals(List.of(expansion(dir, "FIRST",
                        new ExpandedCopyLine(1, "           DISPLAY 'FIRST'."))),
                model.copyInlineExpansions());
        assertEquals(List.of("MIDCOPY.cbl DISPLAY 'A'", "FIRST.cpy DISPLAY 'FIRST'",
                "MIDCOPY.cbl STOP RUN"), statementTexts(model));
    }

    /**
     * A copybook of two lines between two statements keeps both lines in one expansion, and the
     * statement after the COPY follows the copybook's last statement.
     */
    @Test
    void aCopyBetweenTwoStatementsKeepsAllItsLines(@TempDir Path dir) throws Exception {
        CobolSemanticModel model =
                midLineModel(dir, "           DISPLAY 'A' COPY THIRD. DISPLAY 'B'.");

        assertEquals(List.of(expansion(dir, "THIRD",
                        new ExpandedCopyLine(1, "           DISPLAY 'THIRD'."),
                        new ExpandedCopyLine(2, "           DISPLAY 'T2'."))),
                model.copyInlineExpansions());
        assertEquals(List.of("MIDCOPY.cbl DISPLAY 'A'", "THIRD.cpy DISPLAY 'THIRD'",
                "THIRD.cpy DISPLAY 'T2'", "MIDCOPY.cbl DISPLAY 'B'", "MIDCOPY.cbl STOP RUN"),
                statementTexts(model));
    }

    /**
     * A second COPY in the text after a copybook of two lines expands after that copybook's
     * last line, and each COPY keeps its own lines.
     */
    @Test
    void aSecondCopyAfterACopybookOfTwoLinesFollowsItsLastLine(@TempDir Path dir)
            throws Exception {
        CobolSemanticModel model =
                midLineModel(dir, "           DISPLAY 'A' COPY THIRD. DISPLAY 'B' COPY FIRST.");

        assertEquals(List.of(
                        expansion(dir, "THIRD",
                                new ExpandedCopyLine(1, "           DISPLAY 'THIRD'."),
                                new ExpandedCopyLine(2, "           DISPLAY 'T2'.")),
                        expansion(dir, "FIRST",
                                new ExpandedCopyLine(1, "           DISPLAY 'FIRST'."))),
                model.copyInlineExpansions());
        assertEquals(List.of("MIDCOPY.cbl DISPLAY 'A'", "THIRD.cpy DISPLAY 'THIRD'",
                "THIRD.cpy DISPLAY 'T2'", "MIDCOPY.cbl DISPLAY 'B'", "FIRST.cpy DISPLAY 'FIRST'",
                "MIDCOPY.cbl STOP RUN"), statementTexts(model));
    }

    /** Several COPY statements with code between them on one line each expand at that line. */
    @Test
    void severalCopiesWithCodeBetweenThemEachExpand(@TempDir Path dir) throws Exception {
        CobolSemanticModel model = midLineModel(dir,
                "           DISPLAY 'A' COPY FIRST. DISPLAY 'B' COPY SECOND.");

        assertEquals(List.of(
                        expansion(dir, "FIRST", new ExpandedCopyLine(1,
                                "           DISPLAY 'FIRST'.")),
                        expansion(dir, "SECOND", new ExpandedCopyLine(1,
                                "           DISPLAY 'SECOND'."))),
                model.copyInlineExpansions());
    }

    /** A COPY after code on a copybook line expands inside the outer expansion. */
    @Test
    void aCopyAfterCodeOnACopybookLineExpandsInside(@TempDir Path dir) throws Exception {
        CobolSemanticModel model = midLineModel(dir, "           COPY OUTER.");

        assertEquals(List.of(expansion(dir, "OUTER",
                        new ExpandedCopyLine(1, "           DISPLAY 'O'"),
                        new ExpandedCopyLine(1, "           DISPLAY 'FIRST'.",
                                Optional.of(dir.resolve("FIRST.cpy").toString())))),
                model.copyInlineExpansions());
    }

    /** Two COPY statements on one copybook line both expand inside the outer expansion. */
    @Test
    void twoCopyStatementsOnOneCopybookLineBothExpand(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("FIRST.cpy"), "           DISPLAY 'FIRST'.\n");
        Files.writeString(dir.resolve("SECOND.cpy"), "           DISPLAY 'SECOND'.\n");
        Files.writeString(dir.resolve("OUTER.cpy"), String.join("\n",
                "           DISPLAY 'OUTER'.",
                "           COPY FIRST. COPY SECOND.",
                "           DISPLAY 'END'.", ""));
        String source = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID.  TWOCOPY.",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           COPY OUTER.",
                "           STOP RUN.",
                "");
        CobolSemanticModel model = new Che4zCobolParser()
                .parse(TestSources.fromText(dir.resolve("TWOCOPY.cbl").toString(), source),
                        List.of(dir))
                .value().orElseThrow();

        assertEquals(List.of(new CopyInlineExpansion(5, "OUTER",
                        dir.resolve("OUTER.cpy").toString(), List.of(
                                new ExpandedCopyLine(1, "           DISPLAY 'OUTER'."),
                                new ExpandedCopyLine(2, ""),
                                new ExpandedCopyLine(1, "           DISPLAY 'FIRST'.",
                                        Optional.of(dir.resolve("FIRST.cpy").toString())),
                                new ExpandedCopyLine(1, "           DISPLAY 'SECOND'.",
                                        Optional.of(dir.resolve("SECOND.cpy").toString())),
                                new ExpandedCopyLine(3, "           DISPLAY 'END'.")))),
                model.copyInlineExpansions());
        assertEquals(List.of("OUTER.cpy DISPLAY 'OUTER'", "FIRST.cpy DISPLAY 'FIRST'",
                        "SECOND.cpy DISPLAY 'SECOND'", "OUTER.cpy DISPLAY 'END'",
                        "TWOCOPY.cbl STOP RUN"),
                statementTexts(model));
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
