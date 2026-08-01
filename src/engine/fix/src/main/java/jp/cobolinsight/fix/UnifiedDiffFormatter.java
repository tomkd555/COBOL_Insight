package jp.cobolinsight.fix;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

/**
 * 原本テキストと修正後テキストの unified diff を算出する。差分計算は java-diff-utils へ一元化する。
 * 行分割は改行(LF)で行い、原本・修正後を同一手順で分割するため、無変更行は差分に現れない。
 *
 * <p>返す各行は着色を含まない素の unified diff である。ヘッダ({@code --- a/<label>} /
 * {@code +++ b/<label>})・ハンクヘッダ({@code @@ ... @@})・追加行({@code +})・削除行
 * ({@code -})・文脈行(先頭空白)から成る。ANSI 着色や HTML への整形は表示側の責務とする。
 */
public final class UnifiedDiffFormatter {

    /** 差分の前後に付ける文脈行数。 */
    private final int contextSize;

    public UnifiedDiffFormatter() {
        this(3);
    }

    public UnifiedDiffFormatter(int contextSize) {
        this.contextSize = contextSize;
    }

    /**
     * {@code label} をファイル名としたヘッダ付きの unified diff 行を返す。差分が無い場合は空リスト。
     */
    public List<String> unifiedDiff(String label, String originalText, String fixedText) {
        List<String> original = Arrays.asList(originalText.split("\n", -1));
        List<String> revised = Arrays.asList(fixedText.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(original, revised);
        return UnifiedDiffUtils.generateUnifiedDiff(
                "a/" + label, "b/" + label, original, patch, contextSize);
    }
}
