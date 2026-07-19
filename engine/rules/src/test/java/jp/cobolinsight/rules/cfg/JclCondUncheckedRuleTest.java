package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.engineapi.finding.Finding;
import jp.cobolinsight.engineapi.finding.FindingLevel;
import jp.cobolinsight.engineapi.jcl.JclExecKind;
import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.jcl.JclStep;
import jp.cobolinsight.engineapi.source.SourcePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R030 JCLの先行ステップ結果未検査の合成fixture検証(JclJobModelを直接構築)。 */
class JclCondUncheckedRuleTest {

    private static JclStep step(String name, int line, Optional<String> cond) {
        return new JclStep(name, JclExecKind.PGM, "PGM" + name, cond, List.of(),
                new SourcePosition("jcl/FIX030.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static jp.cobolinsight.engineapi.spi.AnalysisContext context(JclJobModel job) {
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void detectsSubsequentStepWithoutCond() {
        JclJobModel job = new JclJobModel("FIX030", "jcl/FIX030.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.empty())));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(1, findings.size(),
                () -> "2番目以降のCOND無しステップ1件(先頭は対象外): " + findings);
        assertEquals("R030", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(8, findings.get(0).location().line());
    }

    @Test
    void ignoresSubsequentStepWithCond() {
        JclJobModel job = new JclJobModel("FIX030B", "jcl/FIX030.jcl", Optional.empty(),
                List.of(step("STEP010", 3, Optional.empty()),
                        step("STEP020", 8, Optional.of("(4,LT,STEP010)"))));
        List<Finding> findings = new JclCondUncheckedRule().evaluate(context(job));
        assertEquals(List.of(), findings, () -> "COND句を持つ後続ステップは対象外: " + findings);
    }
}
