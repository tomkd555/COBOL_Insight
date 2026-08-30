package jp.cobolinsight.rules.syntax;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
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

/** Synthetic fixture verification for R024 missing COPY REPLACING substitutions. */
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
            /*  7 */ "           COPY FLDCPY REPLACING ==FLD-REC== BY ==WS-REC==",
            /*  8 */ "               ==FIELD== BY ==XYZ==.",
            /*  9 */ "           COPY TRLCPY REPLACING TRAILING ==REC== BY ==RC2==.",
            /* 10 */ "       LINKAGE SECTION.",
            /* 11 */ "           COPY GOODCPY REPLACING LEADING",
            /* 12 */ "               ==ZZ== BY ==YY==.",
            /* 13 */ "       PROCEDURE DIVISION.",
            /* 14 */ "       0000-MAIN.",
            /* 15 */ "           MOVE SPACES TO WS-FIELD",
            /* 16 */ "           GOBACK.",
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

        assertEquals(List.of(7, 11),
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
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("置換対象 FLD-REC ")),
                "対を複数書いたREPLACING句でも、コピー句に出現する1対目は検出しないこと");
        Finding multiLine = findings.stream()
                .filter(f -> f.location().line() == 11).findFirst().orElseThrow();
        assertTrue(multiLine.message().contains("置換対象 ZZ "),
                "複数行にわたるCOPY文はその先頭行で報告すること: " + multiLine.message());
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("置換対象 CP ")),
                "LEADING指定は前方境界のみで照合し、CP-RECへの前方一致を出現とみなすこと");
        assertTrue(findings.stream().noneMatch(f -> f.message().contains("置換対象 REC ")),
                "TRAILING指定は後方境界のみで照合し、TRL-RECへの後方一致を出現とみなすこと");
    }
}
