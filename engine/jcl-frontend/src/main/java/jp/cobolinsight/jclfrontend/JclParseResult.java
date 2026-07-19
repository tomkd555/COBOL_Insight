package jp.cobolinsight.jclfrontend;

import java.util.List;

/** 1つの JCL ファイルのパース結果。 */
public record JclParseResult(List<ParsedJob> jobs) {
}
