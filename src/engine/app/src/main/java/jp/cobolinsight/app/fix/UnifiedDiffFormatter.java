package jp.cobolinsight.app.fix;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

/**
 * Computes the unified diff between the original text and the fixed text. Diff computation is
 * centralized on java-diff-utils. Lines are split on the newline (LF), and since the original and
 * the fixed text are split by the same procedure, unchanged lines do not appear in the diff.
 *
 * <p>Each line returned is a plain unified diff with no coloring: it consists of headers
 * ({@code --- a/<label>} / {@code +++ b/<label>}), hunk headers ({@code @@ ... @@}), added lines
 * ({@code +}), removed lines ({@code -}), and context lines (leading whitespace). ANSI coloring
 * or formatting into HTML is the display layer's responsibility.
 */
public final class UnifiedDiffFormatter {

    /** The number of context lines placed before and after each diff hunk. */
    private final int contextSize;

    public UnifiedDiffFormatter() {
        this(3);
    }

    public UnifiedDiffFormatter(int contextSize) {
        this.contextSize = contextSize;
    }

    /**
     * Returns the unified diff lines, with headers, using {@code label} as the file name. Returns
     * an empty list when there is no difference.
     */
    public List<String> unifiedDiff(String label, String originalText, String fixedText) {
        List<String> original = Arrays.asList(originalText.split("\n", -1));
        List<String> revised = Arrays.asList(fixedText.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(original, revised);
        return UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + label, "b/" + label, original, patch, contextSize);
    }
}
