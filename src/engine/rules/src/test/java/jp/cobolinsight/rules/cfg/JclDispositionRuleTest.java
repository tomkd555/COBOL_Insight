package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R032 new dataset kept after an abend, on a JclJobModel built directly. */
class JclDispositionRuleTest {

    private static JclDdStatement dd(String name, int line, String disposition) {
        return new JclDdStatement(name, Optional.of("FLW.D250901." + name),
                Optional.ofNullable(disposition),
                new SourcePosition("jcl/FIX032.jcl", line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(JclDdStatement... dds) {
        JclStep step = new JclStep("STEP010", JclExecKind.PGM, "PGM1", Optional.empty(),
                List.of(dds), new SourcePosition("jcl/FIX032.jcl", 3, 1,
                        SourcePosition.UNKNOWN_BYTE_OFFSET));
        JclJobModel job = new JclJobModel("FIX032", "jcl/FIX032.jcl", Optional.empty(),
                List.of(step));
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void reportsNewDatasetWithoutAnAbnormalDisposition() {
        List<Finding> findings = new JclDispositionRule().evaluate(context(
                dd("OUT1", 5, "(NEW,CATLG)"),
                dd("OUT2", 6, "(,CATLG,KEEP)"),
                dd("OUT3", 7, "(NEW,CATLG,DELETE)"),
                dd("OUT4", 8, "(,CATLG,DELETE)"),
                dd("IN1", 9, "SHR"),
                dd("LOG1", 10, "(MOD,CATLG,CATLG)"),
                dd("TMP1", 11, "(NEW,PASS)"),
                dd("SYSOUT", 12, null)));
        assertEquals(List.of(5, 6), findings.stream().map(f -> f.location().line()).toList(),
                () -> findings.toString());
        assertEquals("R032", findings.get(0).ruleId());
        assertEquals(FindingLevel.WARNING, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("DD OUT1"), findings.get(0).message());
    }

    @Test
    void readsTheDispositionTextAsJclDoes() {
        assertTrue(JclDispositionRule.keptAfterAbend("(NEW,KEEP)"));
        assertTrue(JclDispositionRule.keptAfterAbend("(new,catlg,catlg)"));
        assertFalse(JclDispositionRule.keptAfterAbend("(NEW,DELETE)"));
        assertFalse(JclDispositionRule.keptAfterAbend("(OLD,CATLG)"));
        assertFalse(JclDispositionRule.keptAfterAbend("NEW"));
    }
}
