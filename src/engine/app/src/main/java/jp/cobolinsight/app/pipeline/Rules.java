package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.spi.AnalysisContext;
import jp.cobolinsight.core.spi.FixProducer;

import java.util.List;
import java.util.Optional;

/**
 * Evaluates the rules that belong to one command and files their findings under it. A run may
 * evaluate more than one set — the report shows bug detection and SQL advice side by side — so the
 * findings are kept apart by command rather than merged.
 *
 * <p>A finding from a rule that has a fix producer carries the suggested edit, which is what both
 * the SARIF {@code fixes} and {@code fix preview} are built from.
 */
public record Rules(Command command) implements Step {

    @Override
    public void apply(SourceSet s) {
        AnalysisContext context = contextOf(s);
        List<Finding> into = s.ruleFindings(command);
        for (Rule rule : s.rulesFor(command)) {
            FixProducer producer = rule.fix().orElse(null);
            for (Finding finding : rule.evaluate(context)) {
                into.add(producer == null ? finding : withFix(finding, producer, context));
            }
        }
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
