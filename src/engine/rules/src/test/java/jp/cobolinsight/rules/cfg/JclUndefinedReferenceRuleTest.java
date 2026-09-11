package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.jcl.JclDdStatement;
import jp.cobolinsight.core.jcl.JclExecKind;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.jcl.JclMemberMiss;
import jp.cobolinsight.core.jcl.JclOverrideMiss;
import jp.cobolinsight.core.jcl.JclReferbackMiss;
import jp.cobolinsight.core.jcl.JclStep;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R050 the five references a job carries that the expansion could not resolve or could not find. */
class JclUndefinedReferenceRuleTest {

    private static final String FILE = "jcl/FIX050.jcl";

    private static JclStep step(String name, JclExecKind kind, String target, int line,
            Map<String, String> parameters) {
        return step(name, kind, target, line, parameters, List.of());
    }

    private static JclStep step(String name, JclExecKind kind, String target, int line,
            Map<String, String> parameters, List<JclDdStatement> dds) {
        return new JclStep(name, kind, target, Optional.empty(), dds, parameters,
                Optional.empty(), Optional.empty(), Optional.empty(),
                new SourcePosition(FILE, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static JclDdStatement dd(String name, int line) {
        return new JclDdStatement(name, Optional.of("FLW." + name), Optional.empty(),
                new SourcePosition(FILE, line, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    private static AnalysisContext context(JclJobModel job) {
        return CfgFixtures.context(List.of(), Map.of(), List.of(), List.of(job));
    }

    private static JclJobModel job(String name, List<JclStep> steps, List<String> symbols,
            List<JclOverrideMiss> overrides, List<JclReferbackMiss> referbacks,
            List<JclMemberMiss> missing) {
        return new JclJobModel(name, FILE, Optional.empty(), steps, List.of(), List.of(), List.of(),
                List.of(), Map.of(), List.of(), Optional.empty(), List.of(), symbols, Map.of(),
                overrides, referbacks, missing,
                new SourcePosition(FILE, 1, 1, SourcePosition.UNKNOWN_BYTE_OFFSET));
    }

    @Test
    void reportsEverySortOfUnresolvedReference() {
        JclJobModel job = job("FIX050",
                List.of(step("STEP010", JclExecKind.PGM, "PGM1", 5, Map.of("PARM", "'&MODE'")),
                        step("STEP020", JclExecKind.PROC, "PRC999", 9, Map.of()),
                        step("STEP030", JclExecKind.PROC, "PRC998", 11, Map.of())),
                List.of("&MODE"),
                List.of(new JclOverrideMiss(7, "BADSTEP.SYSIN")),
                List.of(new JclReferbackMiss(8, "*.STEP999.OUT1")),
                List.of(new JclMemberMiss(9, JclMemberMiss.PROC, "PRC999"),
                        new JclMemberMiss(10, JclMemberMiss.INCLUDE, "INC001")));
        List<Finding> findings = new JclUndefinedReferenceRule().evaluate(context(job));
        assertEquals(List.of(5, 7, 8, 9, 10, 11),
                findings.stream().map(f -> f.location().line()).sorted().toList(),
                () -> findings.toString());
        assertEquals("R050", findings.get(0).ruleId());
        assertEquals("&MODE に値を与える定義が見つかりません。"
                        + "読めなかった INCLUDE のメンバーが SET で値を与えていることも"
                        + "あります。",
                messageAt(findings, 5));
        assertEquals("INCLUDE メンバー INC001 が見つかりません。"
                        + "このメンバーの内容は解析の対象外です。",
                messageAt(findings, 10));
        // STEP020 is named by a missingMembers entry, so it is reported as the missing member and
        // not a second time as an unexpanded call; STEP030 is not, so it is.
        assertEquals("PROC メンバー PRC999 が見つかりません。"
                        + "このメンバーの内容は解析の対象外です。",
                messageAt(findings, 9));
        assertEquals("PROC PRC998 の呼び出しは展開されていません。"
                        + "この呼び出しの中のステップは解析の対象外です。",
                messageAt(findings, 11));
        // The override and the referback are errors; the two analysis gaps are notes, as R048 is,
        // and so is &MODE while the INCLUDE member INC001 that could have SET it went unread.
        assertEquals(List.of(7, 8), levelled(findings, FindingLevel.ERROR));
        assertEquals(List.of(5, 9, 10, 11), levelled(findings, FindingLevel.NOTE));
    }

    /**
     * A job-level INCLUDE puts the member's SET statements into the job's scope, so a symbol the
     * walk could not answer may be answered by the member it could not read. An unread PROC member
     * cannot hold that definition, and leaves the symbol an error.
     */
    @Test
    void reportsASymbolAsAnErrorWhileOnlyAProcMemberWentUnread() {
        JclJobModel job = job("FIX050E",
                List.of(step("STEP010", JclExecKind.PGM, "PGM1", 5, Map.of("PARM", "'&MODE'")),
                        step("STEP020", JclExecKind.PROC, "PRC999", 9, Map.of())),
                List.of("&MODE"), List.of(), List.of(),
                List.of(new JclMemberMiss(9, JclMemberMiss.PROC, "PRC999")));
        List<Finding> findings = new JclUndefinedReferenceRule().evaluate(context(job));
        assertEquals(List.of(5), levelled(findings, FindingLevel.ERROR));
        assertEquals("&MODE に値を与える定義がありません。"
                + "実行時に置き換えられず、JCL エラーになります。", messageAt(findings, 5));
    }

    private static List<Integer> levelled(List<Finding> findings, FindingLevel level) {
        return findings.stream().filter(f -> f.level() == level)
                .map(f -> f.location().line()).sorted().toList();
    }

    /** A reference under a call whose member was never read may be answered by that member. */
    @Test
    void ignoresAnOverrideAndAReferbackUnderACallWhoseMemberIsMissing() {
        JclJobModel job = job("FIX050C",
                List.of(step("STEP010", JclExecKind.PROC, "PRC999", 5, Map.of("PARM.INNER", "'X'"),
                        List.of(dd("BACKREF", 6)))),
                List.of(),
                List.of(new JclOverrideMiss(5, "INNER.SYSIN")),
                List.of(new JclReferbackMiss(6, "*.INNER.OUT1")),
                List.of(new JclMemberMiss(5, JclMemberMiss.PROC, "PRC999")));
        List<Finding> findings = new JclUndefinedReferenceRule().evaluate(context(job));
        assertEquals(List.of(5), findings.stream().map(f -> f.location().line()).toList(),
                () -> "only the missing member itself: " + findings);
        assertEquals(FindingLevel.NOTE, findings.get(0).level());
    }

    /** &MODE is not spelled by &MODEL, so the finding does not land on the step that holds it. */
    @Test
    void matchesASymbolOnAWordBoundary() {
        JclJobModel job = job("FIX050D",
                List.of(step("STEP010", JclExecKind.PGM, "PGM1", 5, Map.of("PARM", "'&MODEL'")),
                        step("STEP020", JclExecKind.PGM, "PGM2", 9, Map.of("PARM", "'&MODE'"))),
                List.of("&MODE"), List.of(), List.of(), List.of());
        List<Finding> findings = new JclUndefinedReferenceRule().evaluate(context(job));
        assertEquals(List.of(9), findings.stream().map(f -> f.location().line()).toList(),
                () -> findings.toString());
    }

    private static String messageAt(List<Finding> findings, int line) {
        return findings.stream().filter(f -> f.location().line() == line)
                .map(Finding::message).findFirst().orElseThrow();
    }

    @Test
    void ignoresAProcCallTheExpansionLeftABodyFor() {
        JclJobModel job = job("FIX050B",
                List.of(step("STEP010", JclExecKind.PROC, "PRC001", 5, Map.of()),
                        step("STEP010.INNER", JclExecKind.PGM, "PGM1", 20, Map.of())),
                List.of(), List.of(), List.of(), List.of());
        assertEquals(List.of(), new JclUndefinedReferenceRule().evaluate(context(job)));
    }
}
