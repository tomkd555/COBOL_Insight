package jp.cobolinsight.transpile.emit;

/**
 * Normalizes a COBOL name into an identifier valid in both Python and Java. Since both languages
 * accept Unicode identifiers (including Japanese), characters not usable in an identifier (such as
 * a hyphen) are replaced with an underscore, and an underscore is prepended if the name starts with a digit.
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
