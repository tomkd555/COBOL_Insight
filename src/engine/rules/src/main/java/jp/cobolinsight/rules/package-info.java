/**
 * ServiceLoaderで発見するルールプラグインを置く。構文・CFG・データフローの3段階でfindingsを算出し、
 * 各ルールが任意で FixProducer を提供する。findingsはSARIF形式で出力する。
 */
package jp.cobolinsight.rules;
