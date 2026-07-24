package jp.cobolinsight.transpile.proc;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.ArrayList;
import java.util.List;

/**
 * 原ソーステキストから行範囲を切り出す。意味モデルに含まれない詳細(inline PERFORM VARYING の
 * VARYING/FROM/BY 句など)を range から復元するために使う。行は1始まり。
 */
public final class SourceSlicer {

    private final String[] lines;

    public SourceSlicer(String sourceText) {
        this.lines = sourceText.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /** range の開始行から終了行までを空白1つで連結して返す(桁は無視し行全体を切り出す)。 */
    public String linesOf(SourceRange range) {
        int start = range.start().line();
        int end = range.end().line();
        StringBuilder sb = new StringBuilder();
        for (int line = start; line <= end && line <= lines.length; line++) {
            if (line >= 1) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(lines[line - 1].trim());
            }
        }
        return sb.toString();
    }

    /** range の各行を前後空白を除いてそのまま返す(直訳不能ブロックの原文コメント保存に使う)。 */
    public List<String> rawLinesOf(SourceRange range) {
        List<String> result = new ArrayList<>();
        int start = range.start().line();
        int end = range.end().line();
        for (int line = start; line <= end && line <= lines.length; line++) {
            if (line >= 1) {
                result.add(lines[line - 1].trim());
            }
        }
        return result;
    }
}
