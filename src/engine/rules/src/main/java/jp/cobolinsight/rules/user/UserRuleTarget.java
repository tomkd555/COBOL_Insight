package jp.cobolinsight.rules.user;

import jp.cobolinsight.engineapi.source.AssetKind;

/**
 * 利用者定義ルールが走査する資産の種別。lint がテキスト索引へ載せるのは COBOL 本体・コピー句・
 * BMS の3種であり、JCL は lint の対象外のためここにも無い。
 */
public enum UserRuleTarget {
    COBOL,
    COPYBOOK,
    BMS;

    /**
     * パスの拡張子から種別を決める。拡張子表は {@link AssetKind} が唯一の正であり、
     * ここはその写しを持たない。対象外の種別(JCL)と未知の拡張子には null を返す。
     */
    public static UserRuleTarget ofPath(String path) {
        AssetKind kind = AssetKind.ofFileName(path);
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case COBOL -> COBOL;
            case COPYBOOK -> COPYBOOK;
            case BMS -> BMS;
            case JCL -> null;
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
