package jp.cobolinsight.transpile.emit;

import java.util.ArrayList;
import java.util.List;

/**
 * 88レベル条件名を親項目の値比較で表す述語メソッドの言語非依存な記述。parentGetter は比較対象の
 * 親アクセサの getter 名、parentKind は親項目の符号化区分(文字列比較か数値比較かの判定に使う)、
 * values は VALUE 句の値(引用符付き literal や "low THRU high" 範囲をそのまま保持)。
 */
public record ConditionSpec(String memberName, String parentGetter, FieldKind parentKind,
        List<String> values, List<OccursDim> dims, int sourceLine) {

    public ConditionSpec {
        values = List.copyOf(values);
        dims = List.copyOf(dims);
    }

    /** 親アクセサへ渡す index 仮引数名(i0, i1, ...)。 */
    public List<String> indexParams() {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            params.add("i" + i);
        }
        return params;
    }
}
