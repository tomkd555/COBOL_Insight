package jp.cobolinsight.rules.dataflow;

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

/** R020 動的SQLへの外部入力の未検証組込の合成fixture検証。 */
class DynamicSqlTaintRuleTest {

    @TempDir
    Path tempDir;

    private static String program(String programId, String... procedure) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. " + programId + ".",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-COND     PIC X(20).",
                "       01  WS-DATE     PIC X(08).",
                "       01  WS-DYN-SQL  PIC X(100).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA."));
        sb.append("\n");
        for (String line : procedure) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private List<Finding> run(String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return new DynamicSqlTaintRule()
                .evaluate(DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    @Test
    void detectsExternalInputConcatenatedIntoDynamicSql() {
        String text = program("F020A",
                "           ACCEPT WS-COND",
                "           STRING 'SELECT * FROM T WHERE C = '",
                "                  DELIMITED BY SIZE",
                "                  WS-COND",
                "                  DELIMITED BY SIZE",
                "                  INTO WS-DYN-SQL",
                "           END-STRING",
                "           EXEC SQL",
                "               EXECUTE IMMEDIATE :WS-DYN-SQL",
                "           END-EXEC",
                "           STOP RUN.");
        List<Finding> findings = run("F020A", text);
        assertEquals(1, findings.size(), () -> "汚染ホスト変数の組込を1件検出すること: " + findings);
        assertEquals("R020", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("WS-DYN-SQL"), findings.get(0).message());
    }

    @Test
    void ignoresSystemRegisterDerivedHostVariable() {
        String text = program("F020B",
                "           ACCEPT WS-DATE FROM DATE",
                "           EXEC SQL",
                "               EXECUTE IMMEDIATE :WS-DATE",
                "           END-EXEC",
                "           STOP RUN.");
        assertEquals(List.of(), run("F020B", text),
                "システムレジスタ(ACCEPT FROM DATE)由来のホスト変数は対象外");
    }

    @Test
    void ignoresUntaintedHostVariable() {
        String text = program("F020C",
                "           MOVE 'SELECT 1 FROM SYSIBM.SYSDUMMY1' TO WS-DYN-SQL",
                "           EXEC SQL",
                "               EXECUTE IMMEDIATE :WS-DYN-SQL",
                "           END-EXEC",
                "           STOP RUN.");
        assertEquals(List.of(), run("F020C", text),
                "外部入力に由来しないホスト変数は対象外");
    }
}
