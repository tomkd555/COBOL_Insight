package jp.cobolinsight.frontend.sql;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mangled SQL together with its bidirectional map.
 *
 * @param sql           SQL text with host variables replaced by sequential tokens (:HV1, :HV2, ...)
 * @param hostVariables host variable correspondences in order of appearance (the same original data
 *                      name gets the same token)
 */
public record MangledSql(String sql, List<HostVariableReference> hostVariables) {

    /** Restores this instance's own SQL text tokens back to their original data names and returns it. */
    public String restore() {
        return restore(sql);
    }

    /** Restores tokens (:HVn) in arbitrary text back to their original data names. Does not replace inside string literals. */
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

    /** Tokens eligible for restoration are limited to the :HVn form, so only ASCII alphanumerics count as name characters. */
    private static boolean isTokenChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
