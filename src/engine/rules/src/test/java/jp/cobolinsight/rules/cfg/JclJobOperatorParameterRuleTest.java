package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R053 RESTART and TYPRUN left on the JOB card. */
class JclJobOperatorParameterRuleTest {

    private static final String FILE = "jcl/FIX053.jcl";

    /** Not line 1: the JOB card of the second job of a member, so the position has to be read. */
    private static final int JOB_CARD_LINE = 12;

    private static AnalysisContext context(Map<String, String> parameters) {
        JclStep step = new JclStep("STEP010", JclExecKind.PGM, "PGM1", Optional.empty(), List.of(),
                new SourcePosition(FILE, 5, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
        JclJobModel job = new JclJobModel("FIX053", FILE, Optional.empty(), List.of(step),
                List.of(), List.of(), List.of(), List.of(), parameters, List.of(), Optional.empty(),
                List.of(), List.of(), Map.of(), List.of(), List.of(), List.of(),
                new SourcePosition(FILE, JOB_CARD_LINE, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void reportsRestartAndTyprunAtTheJobCard() {
        List<Finding> findings = new JclJobOperatorParameterRule().evaluate(
                context(Map.of("CLASS", "A", "RESTART", "STEP020", "TYPRUN", "SCAN")));
        assertEquals(2, findings.size(), () -> findings.toString());
        assertEquals("R053", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertEquals(JOB_CARD_LINE, findings.get(0).location().line());
        assertTrue(findings.get(0).message().contains("RESTART=STEP020"),
                findings.get(0).message());
        assertTrue(findings.get(1).message().contains("TYPRUN=SCAN"), findings.get(1).message());
        assertTrue(findings.get(1).message().endsWith("業務処理を実行しないまま正常終了します。"),
                findings.get(1).message());
    }

    /** TYPRUN=HOLD leaves the job waiting, which is not a normal end. */
    @Test
    void saysWhatTyprunHoldDoesRatherThanCallingItANormalEnd() {
        List<Finding> findings = new JclJobOperatorParameterRule().evaluate(
                context(Map.of("CLASS", "A", "TYPRUN", "HOLD")));
        assertEquals(1, findings.size(), () -> findings.toString());
        assertTrue(findings.get(0).message().endsWith(
                        "ジョブが待ち状態のまま実行されず、解放するまで業務処理が進みません。"),
                findings.get(0).message());
    }

    @Test
    void ignoresAJobCardWithoutThem() {
        assertEquals(List.of(), new JclJobOperatorParameterRule().evaluate(
                context(Map.of("CLASS", "A", "MSGCLASS", "X"))));
    }
}
