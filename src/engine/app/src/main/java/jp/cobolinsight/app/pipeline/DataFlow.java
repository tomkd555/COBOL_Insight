package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.dataflow.DataFlowEngine;
import jp.cobolinsight.core.rule.Needs;

/** Solves the inter-procedural data flow, when a rule of this run reads the facts. */
public final class DataFlow implements Step {

    @Override
    public void apply(SourceSet s) {
        if (!s.requires(Needs.DATAFLOW)) {
            return;
        }
        s.flows(DataFlowEngine.analyzeAll(s.programs(), s.cfgs()));
    }
}
