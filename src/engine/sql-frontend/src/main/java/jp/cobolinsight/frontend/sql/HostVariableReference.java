package jp.cobolinsight.frontend.sql;

/**
 * Mangling correspondence for a single host variable.
 *
 * @param token         the token name after mangling (e.g. HV1; appears as :HV1 in SQL)
 * @param dataName      the original data name (e.g. WS-CUST-ID, WS-在庫数)
 * @param indicatorName the original data name of the indicator variable, or null if there is none
 */
public record HostVariableReference(String token, String dataName, String indicatorName) {

    /** Returns the notation as it appears in the original source (e.g. :WS-CUST-ID, :HOST:IND). */
    public String originalText() {
        return indicatorName == null ? ":" + dataName : ":" + dataName + ":" + indicatorName;
    }
}
