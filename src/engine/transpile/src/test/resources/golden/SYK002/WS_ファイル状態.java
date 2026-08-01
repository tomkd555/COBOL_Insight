package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK002 のデータ部レコード WS-ファイル状態 を逐語対訳した自動生成コード。 */
public final class WS_ファイル状態 {

    /** 01 WS-ファイル状態 総バイト長 4。 */
    public static final int LENGTH = 4;

    private final byte[] data;

    public WS_ファイル状態() {
        this.data = new byte[4];
    }

    public WS_ファイル状態(byte[] source) {
        this.data = Arrays.copyOf(source, 4);
    }

    public byte[] data() {
        return data;
    }

    // WS-ORDVALID-STATUS
    public String get_WS_ORDVALID_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 2);
    }
    public void set_WS_ORDVALID_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 2, value);
    }

    // WS-MASTER-STATUS
    public String get_WS_MASTER_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 2, 2);
    }
    public void set_WS_MASTER_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 2, 2, value);
    }
}
