package jp.cobolinsight.frontend.jcl;

import java.util.List;

/** 1つの JCL ファイルのパース結果。 */
public record JclParseResult(List<ParsedJob> jobs) {
}
