package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.source.SourceRange;
import jp.cobolinsight.rules.sarif.SarifWriter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalises the findings of one command and renders them as SARIF 2.1.0.
 *
 * <p>Normalising means the output is the same for the same input: every path — the finding's own,
 * each step of a taint path, each edit range of a fix — is rewritten relative to the asset folder,
 * the COPY search paths or the procedure libraries, and the findings are ordered by file, line,
 * column, rule and message.
 */
public record Sarif(Command command) implements Step {

    @Override
    public void apply(SourceSet s) {
        // The whole walk names the files, so a finding keeps the path a whole-folder run gives it
        // even when a scope left its file out of the units.
        Map<Path, String> relByAbs = s.relPathByAbsPath();
        List<Path> searchPaths = s.copybookSearchPaths();
        List<Finding> ruleFindings = s.ruleFindings(command);
        normalize(ruleFindings, relByAbs, searchPaths);

        // toJson sorts its own copy, so the merged list needs no sort of its own here; normalize's
        // sort above stays because s.findings() and s.ruleFindings(command) are read by more than
        // just this writer (LintRunner exposes ruleFindings(SQL_LINT) without re-sorting it).
        List<Finding> all = new ArrayList<>(ruleFindings);
        if (command != Command.SQL_LINT) {
            // Decode/parse failures are reported once, in the LINT SARIF; SQL_LINT carries only
            // its own rule findings, so a failure is not counted three times (DB + both files).
            normalize(s.findings(), relByAbs, searchPaths);
            all.addAll(s.findings());
        }
        // The pipeline's own finding ids are not rules, so the catalogue holds no descriptor for
        // them; without one a reader of the SARIF has neither a name nor help text for the id.
        // Only the document that carries those findings lists them.
        List<Rule> descriptors = new ArrayList<>(s.rulesFor(command));
        if (command != Command.SQL_LINT) {
            descriptors.addAll(PipelineDiagnostics.all());
        }
        // The scope goes into the file itself: a reader who has only the SARIF would otherwise
        // take a run over one folder for a run over the whole estate.
        s.sarif(command, SarifWriter.toJson(descriptors, all, s.options().scope().written()));
    }

    private static void normalize(List<Finding> findings, Map<Path, String> relByAbs,
            List<Path> searchPaths) {
        List<Finding> normalized = findings.stream()
                .map(finding -> relativize(finding, relByAbs, searchPaths)).toList();
        findings.clear();
        findings.addAll(normalized);
        findings.sort(SarifWriter.findingOrder());
    }

    /** Rewrites a finding, its taint path and its fix edits to asset-folder-relative paths. */
    static Finding relativize(Finding finding, Map<Path, String> relByAbs, List<Path> searchPaths) {
        List<CodeFlow> codeFlows = new ArrayList<>();
        for (CodeFlow codeFlow : finding.codeFlows()) {
            List<CodeFlowStep> steps = new ArrayList<>();
            for (CodeFlowStep step : codeFlow.steps()) {
                steps.add(new CodeFlowStep(relativize(step.position(), relByAbs, searchPaths),
                        step.message()));
            }
            codeFlows.add(new CodeFlow(steps));
        }
        List<FixSuggestion> fixes = new ArrayList<>();
        for (FixSuggestion fix : finding.fixes()) {
            List<TextEdit> edits = new ArrayList<>();
            for (TextEdit edit : fix.edits()) {
                edits.add(new TextEdit(new SourceRange(
                        relativize(edit.range().start(), relByAbs, searchPaths),
                        relativize(edit.range().end(), relByAbs, searchPaths)),
                        edit.replacement()));
            }
            fixes.add(new FixSuggestion(fix.description(), edits));
        }
        return new Finding(finding.ruleId(), finding.level(), finding.message(),
                relativize(finding.location(), relByAbs, searchPaths), codeFlows, fixes);
    }

    /**
     * Replaces an absolute file with its path relative to the asset folder. A file the folder
     * cannot account for is tried against each COPY search path the caller passed; one that
     * matches none stays absolute. A PROC or INCLUDE member reached through {@code --proc-path} is
     * one of those: relative to its library it would read as a file of the asset folder's root,
     * and the SARIF names no base directory that would say otherwise.
     */
    static SourcePosition relativize(SourcePosition position, Map<Path, String> relByAbs,
            List<Path> searchPaths) {
        Path file;
        try {
            file = Path.of(position.file());
        } catch (InvalidPathException e) {
            return position;
        }
        if (!file.isAbsolute()) {
            return position;
        }
        Path normalized = file.normalize();
        String rel = relByAbs.get(normalized);
        if (rel == null) {
            for (Path dir : searchPaths) {
                Path base = dir.toAbsolutePath().normalize();
                if (normalized.startsWith(base)) {
                    rel = base.relativize(normalized).toString().replace('\\', '/');
                    break;
                }
            }
        }
        if (rel == null) {
            return position;
        }
        return new SourcePosition(rel, position.line(), position.column(), position.byteOffset());
    }
}
