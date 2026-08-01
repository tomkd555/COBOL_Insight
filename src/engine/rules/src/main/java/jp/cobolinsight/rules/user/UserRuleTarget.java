package jp.cobolinsight.rules.user;

import java.util.Locale;

/**
 * 利用者定義ルールが走査する資産の種別。lint がテキスト索引へ載せるのは COBOL 本体・コピー句・
 * BMS の3種であり、JCL は lint の対象外のためここにも無い。
 */
public enum UserRuleTarget {
    COBOL,
    COPYBOOK,
    BMS;

    /**
     * パスの拡張子から種別を決める。対応は cli の SourceDiscovery が再帰探索で使う拡張子と
     * 同一であり、片方だけを増やすと利用者定義ルールの対象が走査結果とずれる。
     * 対象外の拡張子(JCL など)には null を返す。
     */
    public static UserRuleTarget ofPath(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        return switch (path.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "cbl", "cob", "cobol" -> COBOL;
            case "cpy", "copy" -> COPYBOOK;
            case "bms" -> BMS;
            default -> null;
        };
    }

    /** 定義ファイルの種別名から引く。扱えない名前には null を返す。 */
    public static UserRuleTarget of(String name) {
        for (UserRuleTarget target : values()) {
            if (target.name().equals(name)) {
                return target;
            }
        }
        return null;
    }
}
