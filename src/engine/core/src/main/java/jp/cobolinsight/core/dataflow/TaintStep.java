package jp.cobolinsight.core.dataflow;

import jp.cobolinsight.core.cfg.CfgNode;

import java.util.Objects;
import java.util.Optional;

/**
 * 汚染伝播経路の1歩。node で variable が汚染を得たことを表す。from はその汚染の直接の伝播元に
 * なった変数で、汚染源(外部入力の受信)では empty。
 */
public record TaintStep(CfgNode node, String variable, Optional<String> from) {

    public TaintStep {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(from, "from");
    }
}
