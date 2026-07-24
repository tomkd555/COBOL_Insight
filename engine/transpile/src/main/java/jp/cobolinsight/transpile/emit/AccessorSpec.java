package jp.cobolinsight.transpile.emit;

import java.util.ArrayList;
import java.util.List;

/**
 * 1つの基本項目に対する get/set アクセサの言語非依存な記述。baseOffset は OCCURS の index 0 を
 * 基準とするレコード先頭からの絶対バイトオフセット、byteLength は1要素分のバイト長。dims は自身を
 * 囲む OCCURS 集団の次元列(外側から内側)で、index 番目の要素は baseOffset + Σ index_k×stride_k に位置する。
 */
public record AccessorSpec(String cobolName, String memberName, int baseOffset, int byteLength,
        FieldKind kind, boolean signed, List<OccursDim> dims, int sourceLine) {

    public AccessorSpec {
        dims = List.copyOf(dims);
    }

    /** OCCURS 次元ごとの index 仮引数名(i0, i1, ...)。 */
    public List<String> indexParams() {
        List<String> params = new ArrayList<>();
        for (int i = 0; i < dims.size(); i++) {
            params.add("i" + i);
        }
        return params;
    }

    /** バイトオフセットを表す式。OCCURS が無ければ定数、あれば "base + i0 * stride0 + ..." 形式。 */
    public String offsetExpression() {
        StringBuilder sb = new StringBuilder();
        sb.append(baseOffset);
        for (int i = 0; i < dims.size(); i++) {
            sb.append(" + i").append(i).append(" * ").append(dims.get(i).stride());
        }
        return sb.toString();
    }
}
