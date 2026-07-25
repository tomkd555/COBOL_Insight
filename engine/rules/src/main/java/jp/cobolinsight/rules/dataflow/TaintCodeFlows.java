package jp.cobolinsight.rules.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.dataflow.TaintStep;
import jp.cobolinsight.engineapi.finding.CodeFlow;
import jp.cobolinsight.engineapi.finding.CodeFlowStep;
import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.source.SourcePosition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 汚染追跡由来の finding へ載せる経路(SARIF の codeFlows)を組む共有ユーティリティ。経路の
 * 途中の歩はパーサーが与えた文の原位置を指し、sink の歩は finding の物理位置と同じ位置を指す。
 */
final class TaintCodeFlows {

    private TaintCodeFlows() {
    }

    /**
     * 汚染下の変数ごとに、汚染源から sink までの経路を1本ずつ組む。sinkAction は sink の歩の
     * 説明で「(変数) を」に続けて用いる語句。機密(SENSITIVE)の汚染源は文を持たない宣言のため、
     * 経路の先頭へデータ部の宣言位置を1歩足す。
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
