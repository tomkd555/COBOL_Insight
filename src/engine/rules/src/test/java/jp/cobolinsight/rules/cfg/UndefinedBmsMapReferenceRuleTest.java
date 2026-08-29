package jp.cobolinsight.rules.cfg;

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

/** R031 BMS 定義に無いマップへの SEND MAP の合成fixture検証。 */
class UndefinedBmsMapReferenceRuleTest {

    @TempDir
    Path tempDir;

    private static final List<BmsMapset> MAPSETS = List.of(
            new BmsMapset("FIXSET1", "FIXSET1.bms",
                    List.of(new BmsMap("FIXM01", 24, 80, List.of()))));

    private static String program(String programId, String map) {
        return String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  FIXM01I PIC X(10).",
                "       PROCEDURE DIVISION.",
                "       0000-MAIN.",
                "           EXEC CICS SEND",
                "                MAP('" + map + "')",
                "                MAPSET('FIXSET1')",
                "           END-EXEC",
                "           GOBACK.",
                "");
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = CfgFixtures.parse(tempDir, programId + ".cbl", text);
        return new UndefinedBmsMapReferenceRule().evaluate(CfgFixtures.context(List.of(model),
                Map.of(model.sourceFile(), text), MAPSETS, List.of()));
    }

    @Test
    void detectsSendMapNamingAMapThatIsNotDefined() {
        String text = program("FIX031", "FIXM99");
        List<Finding> findings = run("FIX031", text);
        assertEquals(1, findings.size(), () -> "定義に無いマップ1件を検出すること: " + findings);
        assertEquals("R031", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertEquals(9, findings.get(0).location().line(), "MAP オペランドの行で報告すること");
        assertTrue(findings.get(0).message().contains("FIXM99"), findings.get(0).message());
    }

    @Test
    void ignoresSendMapNamingADefinedMap() {
        assertEquals(List.of(), run("FIX031B", program("FIX031B", "FIXM01")),
                "BMS に定義のあるマップは対象外");
    }
}
