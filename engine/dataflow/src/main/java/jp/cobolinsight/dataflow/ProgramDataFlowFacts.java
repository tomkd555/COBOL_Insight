package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.cfg.CfgNode;
import jp.cobolinsight.engineapi.dataflow.ProgramDataFlow;
import jp.cobolinsight.engineapi.dataflow.TaintKind;
import jp.cobolinsight.engineapi.dataflow.ValueInterval;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ProgramDataFlow} の実装。不動点解析で確定した各ノードの事実集合を保持し、CfgNode の
 * 同一性で引く。区間値域(intervalAt)はノード入口の変数区間を保持し、追跡外は empty を返す。
 */
final class ProgramDataFlowFacts implements ProgramDataFlow {

    private final String programId;
    private final Map<CfgNode, DefUse> defUseByNode;
    private final Map<CfgNode, Set<Definition>> reachingIn;
    private final Map<CfgNode, Set<String>> externalTaintIn;
    private final Map<CfgNode, Set<String>> sensitiveTaintIn;
    private final Map<CfgNode, Set<String>> liveOut;
    private final Map<CfgNode, Map<String, ValueInterval>> intervalIn;

    ProgramDataFlowFacts(String programId, Map<CfgNode, DefUse> defUseByNode,
            Map<CfgNode, Set<Definition>> reachingIn, Map<CfgNode, Set<String>> externalTaintIn,
            Map<CfgNode, Set<String>> sensitiveTaintIn, Map<CfgNode, Set<String>> liveOut,
            Map<CfgNode, Map<String, ValueInterval>> intervalIn) {
        this.programId = programId;
        this.defUseByNode = defUseByNode;
        this.reachingIn = reachingIn;
        this.externalTaintIn = externalTaintIn;
        this.sensitiveTaintIn = sensitiveTaintIn;
        this.liveOut = liveOut;
        this.intervalIn = intervalIn;
    }

    @Override
    public String programId() {
        return programId;
    }

    @Override
    public boolean mayReachUninitialized(CfgNode useNode, String varName) {
        String var = normalize(varName);
        Set<Definition> reaching = reachingIn.get(useNode);
        if (reaching == null) {
            return false;
        }
        for (Definition d : reaching) {
            if (d.synthetic() && d.variable().equals(var)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<ValueInterval> intervalAt(CfgNode node, String varName) {
        Map<String, ValueInterval> state = intervalIn.get(node);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.get(normalize(varName)));
    }

    @Override
    public Set<String> taintedAt(CfgNode node, TaintKind kind) {
        Map<CfgNode, Set<String>> source =
                kind == TaintKind.SENSITIVE ? sensitiveTaintIn : externalTaintIn;
        return copy(source.get(node));
    }

    @Override
    public Set<String> defsAt(CfgNode node) {
        DefUse defUse = defUseByNode.get(node);
        return defUse == null ? Set.of() : defUse.defs();
    }

    @Override
    public Set<String> usesAt(CfgNode node) {
        DefUse defUse = defUseByNode.get(node);
        return defUse == null ? Set.of() : defUse.uses();
    }

    @Override
    public Set<String> liveOut(CfgNode node) {
        return copy(liveOut.get(node));
    }

    private static String normalize(String varName) {
        return varName.toUpperCase(Locale.ROOT);
    }

    private static Set<String> copy(Set<String> set) {
        return set == null ? Set.of() : Set.copyOf(set);
    }
}
