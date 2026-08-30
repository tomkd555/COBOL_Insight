package jp.cobolinsight.core.finding;

import java.util.Locale;

/** Detection level corresponding to SARIF 2.1.0's result.level. */
public enum FindingLevel {
    ERROR,
    WARNING,
    NOTE;

    public String sarifName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
