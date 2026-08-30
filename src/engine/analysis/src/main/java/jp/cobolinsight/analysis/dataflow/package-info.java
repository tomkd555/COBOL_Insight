/**
 * Builds the control flow graph (CFG), normalizes GO TO (Hendren's algorithm), and runs the
 * fixed-point analyses (reaching definitions, interval value ranges, taint tracking, liveness)
 * that share a monotone transfer function.
 */
package jp.cobolinsight.analysis.dataflow;
