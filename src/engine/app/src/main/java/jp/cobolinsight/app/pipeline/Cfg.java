package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.analysis.dataflow.CfgBuilder;
import jp.cobolinsight.core.cfg.ControlFlowGraphs;
import jp.cobolinsight.core.rule.Needs;

/**
 * Builds one control flow graph per parsed program, when a rule of this run reads them. Data flow
 * is solved over these graphs, so asking for the facts asks for the graphs too.
 */
public final class Cfg implements Step {

    @Override
    public void apply(SourceSet s) {
        if (!s.requires(Needs.CFG) && !s.requires(Needs.DATAFLOW)) {
            return;
        }
        s.cfgs(new ControlFlowGraphs(s.programs().stream().map(CfgBuilder::build).toList()));
    }
}
