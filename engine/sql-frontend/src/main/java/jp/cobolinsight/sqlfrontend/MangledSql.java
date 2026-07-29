package jp.cobolinsight.sqlfrontend;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * マングリング済みSQLと双方向マップ。
 *
 * @param sql           ホスト変数を連番トークン(:HV1, :HV2, …)へ置き換えたSQLテキスト
 * @param hostVariables 出現順のホスト変数対応(同一原データ名は同一トークン)
 */
public record MangledSql(String sql, List<HostVariableReference> hostVariables) {

    /** 自身のSQLテキストのトークンを原データ名へ復元して返す。 */
    public String restore() {
        return restore(sql);
    }

    /** 任意のテキスト中のトークン(:HVn)を原データ名へ復元して返す。文字列リテラル内は置換しない。 */
    public String restore(String text) {
        Map<String, HostVariableReference> byToken = new HashMap<>();
        for (HostVariableReference ref : hostVariables) {
            byToken.put(ref.token(), ref);
        }
        StringBuilder out = new StringBuilder(text.length());
        int length = text.length();
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\'') {
                int close = SqlTextScanner.findStringEnd(text, i);
                if (close < 0) {
                    out.append(text, i, length);
                    break;
                }
                out.append(text, i, close + 1);
                i = close + 1;
            } else if (c == ':') {
                int j = i + 1;
                while (j < length && isTokenChar(text.charAt(j))) {
                    j++;
                }
                HostVariableReference ref = byToken.get(text.substring(i + 1, j));
                if (ref != null) {
                    out.append(ref.originalText());
                } else {
                    out.append(text, i, j);
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** 復元対象のトークンは :HVn の形に限るため、ASCIIの英数字だけを名前の文字として扱う。 */
    private static boolean isTokenChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
