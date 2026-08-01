package jp.cobolinsight.sqlfrontend;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSqlParser へ渡す前の前処理。ハイフン入り・日本語を含むホスト変数(指示変数 :host:ind を含む)を、
 * JSqlParser が解析できる連番トークン(:HV1, :HV2, …)へ可逆変換する。
 * 同一の原データ名(指示変数の組を含む)には同一トークンを割り当てるため衝突しない。
 */
public final class HostVariableMangler {

    private record ParsedName(String name, int end) {
    }

    public MangleResult mangle(String sqlText) {
        StringBuilder out = new StringBuilder(sqlText.length());
        Map<String, HostVariableReference> byOriginal = new LinkedHashMap<>();
        int length = sqlText.length();
        int i = 0;
        while (i < length) {
            char c = sqlText.charAt(i);
            if (c == '\'') {
                int close = SqlTextScanner.findStringEnd(sqlText, i);
                if (close < 0) {
                    out.append(sqlText, i, length);
                    break;
                }
                out.append(sqlText, i, close + 1);
                i = close + 1;
            } else if (c == ':') {
                ParsedName host = parseName(sqlText, i + 1);
                if (host == null) {
                    return notAnalyzable("ホスト変数名として解釈できない ':' がある", sqlText, i);
                }
                String hostError = validateName(host.name());
                if (hostError != null) {
                    return notAnalyzable("ホスト変数名が不正(" + hostError + ")", sqlText, i);
                }
                int next = host.end();
                String indicator = null;
                if (next < length && sqlText.charAt(next) == ':') {
                    ParsedName ind = parseName(sqlText, next + 1);
                    String indError = ind == null ? "名前がない" : validateName(ind.name());
                    if (indError != null) {
                        return notAnalyzable("指示変数名が不正(" + indError + ")", sqlText, i);
                    }
                    indicator = ind.name();
                    next = ind.end();
                }
                String key = indicator == null ? host.name() : host.name() + ":" + indicator;
                HostVariableReference ref = byOriginal.get(key);
                if (ref == null) {
                    ref = new HostVariableReference("HV" + (byOriginal.size() + 1), host.name(), indicator);
                    byOriginal.put(key, ref);
                }
                out.append(':').append(ref.token());
                i = next;
            } else {
                out.append(c);
                i++;
            }
        }
        return new MangleResult.Mangled(new MangledSql(out.toString(), List.copyOf(byOriginal.values())));
    }

    private static ParsedName parseName(String text, int from) {
        int i = from;
        while (i < text.length() && isNameChar(text.charAt(i))) {
            i++;
        }
        if (i == from) {
            return null;
        }
        return new ParsedName(text.substring(from, i), i);
    }

    /** 日本語のデータ名も受けるため、Unicode の文字と数字、およびハイフンとアンダースコアを名前の文字とする。 */
    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    /** COBOL のデータ名はハイフンで始まらず終わらず、数字だけにもならない。違反の理由を返し、正しければ null を返す。 */
    private static String validateName(String name) {
        if (name.startsWith("-") || name.endsWith("-")) {
            return "ハイフンで始まる・終わる名前は使えない";
        }
        if (name.chars().allMatch(Character::isDigit)) {
            return "数字だけの名前は使えない";
        }
        return null;
    }

    private static MangleResult notAnalyzable(String message, String sqlText, int offset) {
        // 該当箇所を示す手掛かりとして、位置から20文字までを理由に添える。
        int end = Math.min(sqlText.length(), offset + 20);
        return new MangleResult.NotAnalyzable(
                message + ": 位置 " + offset + " 付近 \"" + sqlText.substring(offset, end) + "\"");
    }
}
