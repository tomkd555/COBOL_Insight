package jp.cobolinsight.core.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;

import java.util.Objects;
import java.util.Optional;

/**
 * One step in a taint-propagation path. Represents that variable acquired taint at node. from is
 * the variable that directly propagated that taint; empty at a taint source (an external-input
 * receive).
 */
public record TaintStep(CfgNode node, String variable, Optional<String> from) {

    public TaintStep {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(from, "from");
    }
}
