package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード WS-ファイル状態 を逐語対訳した自動生成コード。 */
public final class WS_ファイル状態 {

    /** 01 WS-ファイル状態 総バイト長 6。 */
    public static final int LENGTH = 6;

    private final byte[] data;

    public WS_ファイル状態() {
        this.data = new byte[6];
    }

    public WS_ファイル状態(byte[] source) {
        this.data = Arrays.copyOf(source, 6);
    }

    public byte[] data() {
        return data;
    }

    // WS-ORDIN-STATUS
    public String get_WS_ORDIN_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 2);
    }
    public void set_WS_ORDIN_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 2, value);
    }

    // WS-ORDVALID-STATUS
    public String get_WS_ORDVALID_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 2, 2);
    }
    public void set_WS_ORDVALID_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 2, 2, value);
    }

    // WS-ORDERR-STATUS
    public String get_WS_ORDERR_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 4, 2);
    }
    public void set_WS_ORDERR_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 4, 2, value);
    }
}
