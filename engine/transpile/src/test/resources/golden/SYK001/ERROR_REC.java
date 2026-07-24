package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード ERROR-REC を逐語対訳した自動生成コード。 */
public final class ERROR_REC {

    /** 01 ERROR-REC 総バイト長 200。 */
    public static final int LENGTH = 200;

    private final byte[] data;

    public ERROR_REC() {
        this.data = new byte[200];
    }

    public ERROR_REC(byte[] source) {
        this.data = Arrays.copyOf(source, 200);
    }

    public byte[] data() {
        return data;
    }

    // ERR-受注番号
    public String get_ERR_受注番号() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 10);
    }
    public void set_ERR_受注番号(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 10, value);
    }

    // ERR-エラー内容
    public String get_ERR_エラー内容() {
        return CobolRuntime.decodeAlphanumeric(data, 10, 40);
    }
    public void set_ERR_エラー内容(String value) {
        CobolRuntime.encodeAlphanumeric(data, 10, 40, value);
    }

    // FILLER
    public String get_FILLER() {
        return CobolRuntime.decodeAlphanumeric(data, 50, 150);
    }
    public void set_FILLER(String value) {
        CobolRuntime.encodeAlphanumeric(data, 50, 150, value);
    }
}
