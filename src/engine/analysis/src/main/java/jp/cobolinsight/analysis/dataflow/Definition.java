package jp.cobolinsight.analysis.dataflow;

/**
 * One definition from the reaching-definitions analysis. variable is the (already normalized)
 * variable this definition assigns; nodeId is the id of the node performing the definition;
 * synthetic marks it as the "uninitialized definition" synthesized at entry. Holding a node by
 * id rather than identity keeps set equality checks stable.
 */
record Definition(String variable, int nodeId, boolean synthetic) {
}
