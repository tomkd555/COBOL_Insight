package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.bms.BmsField;
import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R033 synthetic fixture verification for a symbolic map item the BMS map does not define. */
class UndefinedSymbolicMapItemRuleTest {

    @TempDir
    Path tempDir;

    private static final List<BmsMapset> MAPSETS = List.of(
            new BmsMapset("FIXSET2", "FIXSET2.bms", List.of(
                    new BmsMap("FIXM02", 24, 80, List.of(
                            new BmsField("MEI01", 1, 1, 10, ""))))));

    /** A symbolic map 01 with one defined field (MEI01) and one undefined field (MEI99). */
    private static String program(String programId, String referenced) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  FIXM02I.",
                "           05  MEI01L PIC S9(4) COMP.",
                "           05  MEI01F PIC X.",
                "           05  MEI01A PIC X.",
                "           05  MEI01I PIC X(10).",
                "           05  MEI99L PIC S9(4) COMP.",
                "           05  MEI99F PIC X.",
                "           05  MEI99A PIC X.",
                "           05  MEI99I PIC X(10).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           MOVE SPACES TO " + referenced,
                "           GOBACK.",
                "");
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new UndefinedSymbolicMapItemRule().evaluate(CfgFixtures.context(List.of(model),
                Map.of(model.sourceFile(), text), MAPSETS, List.of()));
    }

    @Test
    void detectsReferenceToItemUndefinedInTheMap() {
        String text = program("FIX033", "MEI99I");
        List<Finding> findings = run("FIX033", text);
        assertEquals(1, findings.size(), () -> "マップに定義のない項目の参照1件を検出すること: " + findings);
        assertEquals("R033", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(16, findings.get(0).location().line(), "MOVE 文の行で報告すること");
        assertTrue(findings.get(0).message().contains("MEI99I"), findings.get(0).message());
    }

    @Test
    void ignoresReferenceToItemDefinedInTheMap() {
        assertEquals(List.of(), run("FIX033B", program("FIX033B", "MEI01I")),
                "マップに定義のある項目は対象外");
    }
}
