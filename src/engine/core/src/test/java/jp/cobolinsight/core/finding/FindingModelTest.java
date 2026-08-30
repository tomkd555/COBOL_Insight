package jp.cobolinsight.core.finding;

import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingModelTest {

    private static SourcePosition pos() {
        return new SourcePosition("A.cbl", 10, 8, 120);
    }

    @Test
    void severityMapsToSarifLevel() {
        assertEquals(FindingLevel.ERROR, Severity.HIGH.toLevel());
        assertEquals(FindingLevel.WARNING, Severity.MEDIUM.toLevel());
        assertEquals(FindingLevel.NOTE, Severity.LOW.toLevel());
        assertEquals(FindingLevel.WARNING, Severity.ADVISORY.toLevel());
    }

    @Test
    void findingLevelHasSarifName() {
        assertEquals("error", FindingLevel.ERROR.sarifName());
        assertEquals("warning", FindingLevel.WARNING.sarifName());
        assertEquals("note", FindingLevel.NOTE.sarifName());
    }

    @Test
    void findingRejectsBlankRuleIdAndMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> Finding.of(" ", FindingLevel.ERROR, "m", pos()));
        assertThrows(IllegalArgumentException.class,
                () -> Finding.of("R001", FindingLevel.ERROR, "", pos()));
    }

    @Test
    void findingOfHasNoCodeFlowsAndNoFixes() {
        Finding f = Finding.of("R001", FindingLevel.WARNING, "msg", pos());
        assertTrue(f.codeFlows().isEmpty());
        assertTrue(f.fixes().isEmpty());
        assertEquals("A.cbl", f.location().file());
    }

    @Test
    void parseFailureFactoryProducesErrorLevelFinding() {
        Finding f = Finding.parseFailure(SourcePosition.fileStart("B.cbl"), "unexpected token");
        assertEquals(Finding.PARSE_FAILURE_RULE_ID, f.ruleId());
        assertEquals(FindingLevel.ERROR, f.level());
        assertEquals("B.cbl", f.location().file());
    }

    @Test
    void codeFlowRequiresAtLeastOneStep() {
        assertThrows(IllegalArgumentException.class, () -> new CodeFlow(List.of()));
        CodeFlow flow = new CodeFlow(List.of(new CodeFlowStep(pos(), "source of taint")));
        assertEquals(1, flow.steps().size());
    }

    @Test
    void fixSuggestionRequiresDescriptionAndEdits() {
        SourceRange range = new SourceRange(pos(), pos());
        TextEdit edit = new TextEdit(range, "replacement");
        assertThrows(IllegalArgumentException.class, () -> new FixSuggestion(" ", List.of(edit)));
        assertThrows(IllegalArgumentException.class, () -> new FixSuggestion("desc", List.of()));
        FixSuggestion fix = new FixSuggestion("desc", List.of(edit));
        assertEquals("replacement", fix.edits().get(0).replacement());
    }

    @Test
    void textEditAllowsEmptyReplacementForDeletion() {
        TextEdit edit = new TextEdit(new SourceRange(pos(), pos()), "");
        assertEquals("", edit.replacement());
    }

    @Test
    void findingListsAreImmutable() {
        Finding f = Finding.of("R001", FindingLevel.NOTE, "msg", pos());
        assertThrows(UnsupportedOperationException.class,
                () -> f.codeFlows().add(new CodeFlow(List.of(new CodeFlowStep(pos(), "")))));
    }
}
