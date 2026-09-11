package jp.cobolinsight.frontend.sql;

import java.util.Objects;

/**
 * Mangling correspondence for a single host variable.
 *
 * @param token         the token name after mangling (e.g. HV1; appears as :HV1 in SQL)
 * @param dataName      the original data name, qualifier included (e.g. WS-CUST-ID, A OF B)
 * @param indicatorName the original data name of the indicator variable, or null if there is none
 * @param originalText  the reference exactly as the source spells it, from the ':' onwards
 */
public record HostVariableReference(String token, String dataName, String indicatorName,
        String originalText) {

    public HostVariableReference {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(dataName, "dataName");
        Objects.requireNonNull(originalText, "originalText");
    }
}
