package jp.cobolinsight.transpile.emit;

import java.util.ArrayList;
import java.util.List;

/**
 * A language-independent description of the get/set accessor for one elementary item. baseOffset is
 * the absolute byte offset from the start of the record, relative to OCCURS index 0; byteLength is
 * the byte length of one occurrence. dims is the list of dimensions of the enclosing OCCURS groups
 * (outermost to innermost); the element at a given index is located at baseOffset + sum(index_k x stride_k).
 */
public record AccessorSpec(String cobolName, String memberName, int baseOffset, int byteLength,
        FieldKind kind, boolean signed, List<OccursDim> dims, int sourceLine) {

    public AccessorSpec {
        dims = List.copyOf(dims);
    }

    /** Index parameter names for each OCCURS dimension (i0, i1, ...). */
    public List<String> indexParams() {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            params.add("i" + i);
        }
        return params;
    }

    /** The expression representing the byte offset. A constant if there is no OCCURS, otherwise in the form "base + i0 * stride0 + ...". */
    public String offsetExpression() {
        StringBuilder sb = new StringBuilder();
        sb.append(baseOffset);
        for (int i = 0; i < dims.size(); i++) {
            sb.append(" + i").append(i).append(" * ").append(dims.get(i).stride());
        }
        return sb.toString();
    }
}
