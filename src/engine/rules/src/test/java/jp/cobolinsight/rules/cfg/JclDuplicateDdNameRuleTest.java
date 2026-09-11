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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R051 a DD name written twice in one step, against the concatenation that shares one name. */
class JclDuplicateDdNameRuleTest {

    private static final String FILE = "jcl/FIX051.jcl";

    private static JclDdStatement dd(String name, int line, int concatIndex) {
        return new JclDdStatement(name, Optional.of("FLW.D250901." + name + line),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false,
                Map.of(), Map.of(), List.of(), concatIndex,
                new SourcePosition(FILE, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(JclDdStatement... dds) {
        JclStep step = new JclStep("STEP010", JclExecKind.PGM, "PGM1", Optional.empty(),
                List.of(dds), new SourcePosition(FILE, 3, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
        JclJobModel job = new JclJobModel("FIX051", FILE, Optional.empty(), List.of(step));
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    @Test
    void reportsTheSecondDdOfTheSameName() {
        List<Finding> findings = new JclDuplicateDdNameRule().evaluate(context(
                dd("ORDIN", 5, 0), dd("ordin", 6, 0), dd("ORDOUT", 7, 0)));
        assertEquals(List.of(6), findings.stream().map(f -> f.location().line()).toList(),
                () -> findings.toString());
        assertEquals("R051", findings.get(0).ruleId());
        assertEquals(FindingLevel.ERROR, findings.get(0).level());
        assertTrue(findings.get(0).message().contains("DD ordin"), findings.get(0).message());
    }

    @Test
    void ignoresTheNamelessStatementsOfAConcatenation() {
        assertEquals(List.of(), new JclDuplicateDdNameRule().evaluate(context(
                dd("ORDIN", 5, 0), dd("ORDIN", 6, 1), dd("ORDIN", 7, 2))));
    }
}
