package jp.cobolinsight.engineapi.finding;

import java.util.Locale;

/** SARIF 2.1.0 の result.level に対応する検出レベル。 */
public enum FindingLevel {
    ERROR,
    WARNING,
    NOTE;

    public String sarifName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
