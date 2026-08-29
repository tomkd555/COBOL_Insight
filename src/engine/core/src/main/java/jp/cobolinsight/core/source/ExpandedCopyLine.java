package jp.cobolinsight.core.source;

import java.util.Objects;

/**
 * インライン展開後の1行。コピー句内の由来行(1始まり)と、REPLACING を適用した後のテキストを持つ。
 * テキストは前処理を通した後の姿であり、注記行・一連番号欄・識別欄は空白になる。
 */
public record ExpandedCopyLine(int copybookLine, String text) {

    public ExpandedCopyLine {
        if (copybookLine < 1) {
            throw new IllegalArgumentException("copybookLine must be >= 1: " + copybookLine);
        }
        Objects.requireNonNull(text, "text");
    }
}
