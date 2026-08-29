package jp.cobolinsight.core.source;

import java.util.List;

/**
 * COPY 文1件のインライン展開。原本の COPY 文の位置へ、コピー句のどの行が REPLACING 適用後の
 * どのテキストとして現れるかを、展開後の並び順で保持する。
 *
 * <p>{@link CopyExpansionEntry} が展開後ソースの行範囲を持つのに対し、こちらは原本の COPY 文を
 * 起点に、展開される各行の中身まで持つ。原本へコピー句を差し込んだ姿を示す用途に用いる。
 */
public record CopyInlineExpansion(int copyStatementLine, String copybookName, String copybookPath,
        List<ExpandedCopyLine> lines) {

    public CopyInlineExpansion {
        if (copyStatementLine < 1) {
            throw new IllegalArgumentException(
                    "copyStatementLine must be >= 1: " + copyStatementLine);
        }
        if (copybookName == null || copybookName.isBlank()) {
            throw new IllegalArgumentException("copybookName must not be blank");
        }
        if (copybookPath == null || copybookPath.isBlank()) {
            throw new IllegalArgumentException("copybookPath must not be blank");
        }
        lines = List.copyOf(lines);
    }
}
