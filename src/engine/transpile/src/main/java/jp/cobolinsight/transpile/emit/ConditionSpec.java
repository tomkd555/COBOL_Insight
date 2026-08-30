package jp.cobolinsight.transpile.emit;

import java.util.ArrayList;
import java.util.List;

/**
 * A language-independent description of the predicate method that expresses an 88-level condition
 * name as a value comparison against its parent item. parentGetter is the getter name of the parent
 * accessor being compared; parentKind is the parent item's encoding kind (used to decide between a
 * string comparison and a numeric comparison); values holds the VALUE clause values as-is
 * (quoted literals or a "low THRU high" range).
 */
public record ConditionSpec(String memberName, String parentGetter, FieldKind parentKind,
        List<String> values, List<OccursDim> dims, int sourceLine) {

    public ConditionSpec {
        values = List.copyOf(values);
        dims = List.copyOf(dims);
    }

    /** Index parameter names (i0, i1, ...) passed to the parent accessor. */
    public List<String> indexParams() {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            params.add("i" + i);
        }
        return params;
    }
}
