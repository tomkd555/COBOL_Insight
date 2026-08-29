package jp.cobolinsight.frontend.sql;

/**
 * ホスト変数1個のマングリング対応。
 *
 * @param token         マングリング後のトークン名(例 HV1。SQL中では :HV1)
 * @param dataName      原データ名(例 WS-CUST-ID、WS-在庫数)
 * @param indicatorName 指示変数の原データ名。指示変数がなければ null
 */
public record HostVariableReference(String token, String dataName, String indicatorName) {

    /** 原ソース上の表記(例 :WS-CUST-ID、:HOST:IND)を返す。 */
    public String originalText() {
        return indicatorName == null ? ":" + dataName : ":" + dataName + ":" + indicatorName;
    }
}
