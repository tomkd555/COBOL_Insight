package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;
import jp.cobolinsight.core.dataflow.ProgramDataFlow;
import jp.cobolinsight.core.dataflow.TaintKind;
import jp.cobolinsight.core.dataflow.TaintStep;
import jp.cobolinsight.core.finding.CodeFlow;
import jp.cobolinsight.core.finding.CodeFlowStep;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.source.SourcePosition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared utility that builds the path (SARIF's codeFlows) attached to a taint-tracking-derived
 * finding. An intermediate step of the path points to the statement's original position as given
 * by the parser, and the sink step points to the same position as the finding's physical location.
 */
final class TaintCodeFlows {

    private TaintCodeFlows() {
    }

    /**
     * For each tainted variable, builds one path from the taint source to the sink. sinkAction is
     * the phrase used in the sink step's description, following "(変数) を" ("the variable"). Because
     * a SENSITIVE taint source is a declaration with no statement, a step for the data-division
     * declaration position is prepended to the path.
     */
    static List<CodeFlow> of(CobolSemanticModel model, ProgramDataFlow df, CfgNode sinkNode,
            TaintKind kind, Collection<String> variables, SourcePosition sinkPosition,
            String sinkAction) {
        List<CodeFlow> flows = new ArrayList<>();
        for (String variable : variables) {
            List<TaintStep> path = df.taintPathTo(sinkNode, variable, kind);
            List<CodeFlowStep> steps = new ArrayList<>();
            if (kind == TaintKind.SENSITIVE) {
                String declared = path.isEmpty() ? variable : path.get(0).from().orElse(variable);
                declarationOf(model, declared).ifPresent(position -> steps.add(
                        new CodeFlowStep(position, "機密項目 " + declared + " を宣言する")));
            }
            for (TaintStep step : path) {
                step.node().statement().ifPresent(statement -> steps.add(
                        new CodeFlowStep(statement.range().start(), describe(step))));
            }
            steps.add(new CodeFlowStep(sinkPosition, variable + " を" + sinkAction));
            flows.add(new CodeFlow(steps));
        }
        return flows;
    }

    private static String describe(TaintStep step) {
        return step.from()
                .map(from -> from + " から " + step.variable() + " へ汚染が伝播する")
                .orElseGet(() -> step.variable() + " が外部入力を受け取る");
    }

    private static Optional<SourcePosition> declarationOf(CobolSemanticModel model, String name) {
        for (DataItem item : model.dataItems()) {
            Optional<SourcePosition> found = declarationOf(item, name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<SourcePosition> declarationOf(DataItem item, String name) {
        if (item.name().toUpperCase(Locale.ROOT).equals(name)) {
            return Optional.of(item.position());
        }
        for (DataItem child : item.children()) {
            Optional<SourcePosition> found = declarationOf(child, name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
