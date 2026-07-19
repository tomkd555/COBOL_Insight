package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R024 COPY REPLACINGによる置換漏れの合成fixture検証。 */
class CopyReplacingRuleTest {

    @TempDir
    Path tempDir;

    private static final String COPYBOOK = String.join("\n",
            "       01  CP-REC.",
            "           05  CP-FIELD                PIC X(05).",
            "");

    private static final String FLD_COPYBOOK = String.join("\n",
            "       01  FLD-REC.",
            "           05  FLD-FIELD               PIC X(05).",
            "");

    private static final String TRL_COPYBOOK = String.join("\n",
            "       01  TRL-REC.",
            "           05  TRL-ITEM                PIC X(05).",
            "");

    private static final String SOURCE = String.join("\n",
            /*  1 */ "       IDENTIFICATION DIVISION.",
            /*  2 */ "       PROGRAM-ID.  FIX024.",
            /*  3 */ "       ENVIRONMENT DIVISION.",
            /*  4 */ "       DATA DIVISION.",
            /*  5 */ "       WORKING-STORAGE SECTION.",
            /*  6 */ "           COPY GOODCPY REPLACING LEADING ==CP== BY ==WS==.",
            /*  7 */ "           COPY FLDCPY REPLACING ==FIELD== BY ==XYZ==.",
            /*  8 */ "           COPY TRLCPY REPLACING TRAILING ==REC== BY ==RC2==.",
            /*  9 */ "       LINKAGE SECTION.",
            /* 10 */ "           COPY GOODCPY REPLACING LEADING",
            /* 11 */ "               ==ZZ== BY ==YY==.",
            /* 12 */ "       PROCEDURE DIVISION.",
            /* 13 */ "       0000-MAIN.",
            /* 14 */ "           MOVE SPACES TO WS-FIELD",
            /* 15 */ "           GOBACK.",
            "");

    @Test
    void detectsReplacingTargetAbsentFromCopybook() throws IOException {
        Path copybookDir = tempDir.resolve("copybook");
        Files.createDirectories(copybookDir);
        Files.writeString(copybookDir.resolve("GOODCPY.cpy"), COPYBOOK, StandardCharsets.UTF_8);
        Files.writeString(copybookDir.resolve("FLDCPY.cpy"), FLD_COPYBOOK, StandardCharsets.UTF_8);
        Files.writeString(copybookDir.resolve("TRLCPY.cpy"), TRL_COPYBOOK, StandardCharsets.UTF_8);
        CobolSemanticModel model = Fixtures.parse(tempDir, "FIX024.cbl", SOURCE, copybookDir);

        List<Finding> findings = new CopyReplacingRule().evaluate(Fixtures.context(List.of(model),
                Map.of(model.sourceFile(), SOURCE,
                        copybookDir.resolve("GOODCPY.cpy").toString(), COPYBOOK,
                        copybookDir.resolve("FLDCPY.cpy").toString(), FLD_COPYBOOK,
                        copybookDir.resolve("TRLCPY.cpy").toString(), TRL_COPYBOOK)));

        assertEquals(List.of(7, 10),
                findings.stream().map(f -> f.location().line()).sorted().toList(),
                () -> "検出: " + findings);
        for (Finding finding : findings) {
            assertEquals("R024", finding.ruleId());
            assertEquals(FindingLevel.WARNING, finding.level());
        }
        Finding fieldFinding = findings.stream()
                .filter(f -> f.location().line() == 7).findFirst().orElseThrow();
        assertTrue(fieldFinding.message().contains("置換対象 FIELD "),
                "FLD-FIELDの語中一致を出現とみなさず検出すること: " + fieldFinding.message());
        Finding multiLine = findings.stream()
                .filter(f -> f.location().line() == 10).findFirst().orElseThrow();
        assertTrue(multiLine.message().contains("置換対象 ZZ "),
                "複数行にわたるCOPY文はその先頭行で報告すること: " + multiLine.message());
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("置換対象 CP ")),
                "LEADING指定は前方境界のみで照合し、CP-RECへの前方一致を出現とみなすこと");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("置換対象 REC ")),
                "TRAILING指定は後方境界のみで照合し、TRL-RECへの後方一致を出現とみなすこと");
    }
}
