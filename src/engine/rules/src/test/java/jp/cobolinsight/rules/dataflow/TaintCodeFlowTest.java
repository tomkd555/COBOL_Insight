package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.rule.Rule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that findings from taint-tracking rules (R020, R027) retain the path
 * from the taint source to the sink as codeFlows.
 */
class TaintCodeFlowTest {

    @TempDir
    Path tempDir;

    private record Step(int line, String message) {
    }

    private List<Finding> run(Rule rule, String programId, String text) {
        CobolSemanticModel model = DataFlowFixtures.parse(tempDir, programId + ".cbl", text);
        return rule.evaluate(
                DataFlowFixtures.context(List.of(model), Map.of(model.sourceFile(), text)));
    }

    private static List<Step> steps(CodeFlow flow) {
        return flow.steps().stream()
                .map(step -> new Step(step.position().line(), step.message()))
                .toList();
    }

    private static CodeFlow onlyFlow(Finding finding) {
        assertEquals(1, finding.codeFlows().size(),
                () -> "検出1件につき経路1本を持つこと: " + finding.codeFlows());
        return finding.codeFlows().get(0);
    }

    @Test
    void dynamicSqlFindingCarriesTheTaintPathFromAcceptToTheSink() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. C020A.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-COND     PIC X(20).",
                "       01  WS-DYN-SQL  PIC X(100).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
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
                "           STOP RUN.",
                "");
        List<Finding> findings = run(new DynamicSqlTaintRule(), "C020A", text);
        assertEquals(1, findings.size(), () -> "R020 を1件検出すること: " + findings);
        Finding finding = findings.get(0);

        assertEquals(List.of(
                        new Step(9, "WS-COND が外部入力を受け取る"),
                        new Step(10, "WS-COND から WS-DYN-SQL へ汚染が伝播する"),
                        new Step(finding.location().line(),
                                "WS-DYN-SQL を動的SQLの文字列へ組み込む")),
                steps(onlyFlow(finding)),
                "ACCEPT→STRING→動的SQL の順に経路を保持すること");
    }

    @Test
    void sensitiveOutputFindingCarriesTheDeclarationThenEachAssignment() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. C027A.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-CARD-NO  PIC X(16).",
                "       01  WS-WORK     PIC X(16).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           MOVE WS-CARD-NO TO WS-WORK",
                "           DISPLAY WS-WORK",
                "           STOP RUN.",
                "");
        List<Finding> findings = run(new SensitiveDataOutputRule(), "C027A", text);
        assertEquals(1, findings.size(), () -> "R027 を1件検出すること: " + findings);
        Finding finding = findings.get(0);

        assertEquals(List.of(
                        new Step(5, "機密項目 WS-CARD-NO を宣言する"),
                        new Step(9, "WS-CARD-NO から WS-WORK へ汚染が伝播する"),
                        new Step(10, "WS-WORK を出力する")),
                steps(onlyFlow(finding)),
                "宣言→代入→出力 の順に経路を保持すること");
    }

    @Test
    void directOutputOfDeclaredSensitiveItemCarriesDeclarationAndSink() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. C027B.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-CUST-SSN   PIC X(11).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           DISPLAY WS-CUST-SSN",
                "           STOP RUN.",
                "");
        List<Finding> findings = run(new SensitiveDataOutputRule(), "C027B", text);
        assertEquals(1, findings.size(), () -> "R027 を1件検出すること: " + findings);

        assertEquals(List.of(
                        new Step(5, "機密項目 WS-CUST-SSN を宣言する"),
                        new Step(8, "WS-CUST-SSN を出力する")),
                steps(onlyFlow(findings.get(0))),
                "代入を経ない直接出力では宣言と sink の2歩になること");
    }

    @Test
    void codeFlowStepsPointAtTheSourceFileOfTheFinding() {
        String text = String.join("\n",
                "       IDENTIFICATION DIVISION.",
                "       PROGRAM-ID. C027C.",
                "       DATA DIVISION.",
                "       WORKING-STORAGE SECTION.",
                "       01  WS-ACCT-NO  PIC X(10).",
                "       PROCEDURE DIVISION.",
                "       MAIN-PARA.",
                "           DISPLAY WS-ACCT-NO",
                "           STOP RUN.",
                "");
        Finding finding = run(new SensitiveDataOutputRule(), "C027C", text).get(0);

        for (CodeFlowStep step : onlyFlow(finding).steps()) {
            assertEquals(finding.location().file(), step.position().file(),
                    "経路の各歩は検出と同じソースファイルを指すこと");
        }
    }
}
