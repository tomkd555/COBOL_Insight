package jp.cobolinsight.dataflow;

/**
 * 汚染追跡の1事実。variable がノード nodeId で汚染を得たことを表し、fromVariable と fromNodeId は
 * その汚染の直接の伝播元になった事実を指す。ノードの同一性ではなく id で持つことで集合の等価
 * 判定を安定させ、伝播元を事実の値として持つことで有限束を保つ(経路を入れ子で持たない)。
 */
record TaintFact(String variable, int nodeId, String fromVariable, int fromNodeId) {

    /** データ部の宣言に由来する汚染(機密名義)の nodeId。対応する CFG ノードは無い。 */
    static final int DECLARATION = -1;

    /** 伝播元を持たない(汚染源そのものである)ことを表す fromNodeId。 */
    static final int NO_SOURCE = -2;

    static TaintFact declared(String variable) {
        return new TaintFact(variable, DECLARATION, null, NO_SOURCE);
    }

    static TaintFact source(String variable, int nodeId) {
        return new TaintFact(variable, nodeId, null, NO_SOURCE);
    }

    static TaintFact propagated(String variable, int nodeId, TaintFact from) {
        return new TaintFact(variable, nodeId, from.variable(), from.nodeId());
    }
}
