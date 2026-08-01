package jp.cobolinsight.transpile.emit;

/**
 * COBOL 名を Python/Java 双方で有効な識別子へ正規化する。両言語とも Unicode 識別子(日本語を含む)を
 * 受け付けるため、識別子に使えない文字(ハイフン等)を下線へ置換し、先頭が数字なら下線を前置する。
 */
public final class Identifiers {

    private Identifiers() {
    }

    public static String sanitize(String cobolName) {
        if (cobolName == null || cobolName.isBlank()) {
            throw new IllegalArgumentException("cobolName must not be blank");
        }
        StringBuilder sb = new StringBuilder(cobolName.length() + 1);
        for (int i = 0; i < cobolName.length(); i++) {
            char c = cobolName.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }
}
