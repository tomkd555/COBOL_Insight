package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates the rules that belong to one command and files their findings under it. A run may
 * evaluate more than one set — {@code lint} shows bug detection and SQL advice in their own SARIF files — so the
 * findings are kept apart by command rather than merged.
 *
 * <p>A finding from a rule that has a fix producer carries the suggested edit, which is what both
 * the SARIF {@code fixes} and {@code fix} are built from.
 */
public record Rules(Command command) implements Step {

    @Override
    public void apply(SourceSet s) {
        AnalysisContext context = contextOf(s);
        Set<String> scripts = filesOf(s, AssetKind.SQL);
        Set<String> easytrieve = filesOf(s, AssetKind.EASYTRIEVE);
        List<Finding> into = s.ruleFindings(command);
        for (Rule rule : s.rulesFor(command)) {
            boolean judgesScripts = rule.meta().targets().contains(AssetKind.SQL);
            boolean judgesEasytrieve = rule.meta().targets().contains(AssetKind.EASYTRIEVE);
            FixProducer producer = rule.fix().orElse(null);
            for (Finding finding : rule.evaluate(context)) {
                if (!judgesScripts && scripts.contains(finding.location().file())) {
                    continue;
                }
                if (!judgesEasytrieve && easytrieve.contains(finding.location().file())) {
                    continue;
                }
                into.add(producer == null ? finding : withFix(finding, producer, context));
            }
        }
    }

    /**
     * The files of one kind, by the path a finding's location names. A rule states the asset kinds
     * it judges, and every rule that reads the SQL statements sees the statements of a script and
     * of an Easytrieve program beside those of a COBOL program, because they stand in one list.
     * Dropping the findings that land on a kind the rule does not target here keeps that scoping
     * in one place instead of in every rule.
     */
    private static Set<String> filesOf(SourceSet s, AssetKind kind) {
        Set<String> files = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (SourceUnit unit : s.unitsOf(kind)) {
            files.add(unit.absPath().toString());
            files.add(unit.relPath());
        }
        return files;
    }

    static AnalysisContext contextOf(SourceSet s) {
        return AnalysisContext.of(s.programs(), s.jobs(), s.sql(), s.mapsets(), s.callGraph(),
                s.artifactsWith(s.cfgs(), s.flows()));
    }

    /** Attaches the rule's suggested fix. A finding the producer declines is passed through. */
    private static Finding withFix(Finding finding, FixProducer producer,
            AnalysisContext context) {
        Optional<jp.cobolinsight.core.finding.FixSuggestion> fix =
                producer.produce(finding, context);
        return fix.<Finding>map(suggestion -> new Finding(finding.ruleId(), finding.level(),
                        finding.message(), finding.location(), finding.codeFlows(),
                        List.of(suggestion)))
                .orElse(finding);
    }
}
