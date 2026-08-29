package jp.cobolinsight.analysis.dataflow;

/**
 * 到達定義解析の1定義。variable はこの定義が与える変数(正規化済み)、nodeId は定義を行う
 * ノードの id、synthetic は入口で合成した「未初期化定義」であることを表す。ノードの同一性では
 * なく id で持つことで集合の等価判定を安定させる。
 */
record Definition(String variable, int nodeId, boolean synthetic) {
}
