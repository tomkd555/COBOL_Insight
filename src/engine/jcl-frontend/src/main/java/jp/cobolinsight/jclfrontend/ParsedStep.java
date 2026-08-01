package jp.cobolinsight.jclfrontend;

import java.util.List;

/**
 * JCL のステップ。EXEC PGM の場合は programName、EXEC PROC の場合は procName と
 * procSteps(PROC 展開後のステップ)を持つ。condition は COND 句のテキスト表現
 * (COND 句が無いステップでは null)。line は PROC 展開・シンボリック解決後の
 * 作業ファイル上の行番号。
 */
public record ParsedStep(
		String stepName,
		String programName,
		String procName,
		String condition,
		int line,
		List<ParsedDd> ddStatements,
		List<ParsedStep> procSteps) {
}
