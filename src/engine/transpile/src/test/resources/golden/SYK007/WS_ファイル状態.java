package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード WS-ファイル状態 を逐語対訳した自動生成コード。 */
public final class WS_ファイル状態 {

    /** 01 WS-ファイル状態 総バイト長 2。 */
    public static final int LENGTH = 2;

    private final byte[] data;

    public WS_ファイル状態() {
        this.data = new byte[2];
    }

    public WS_ファイル状態(byte[] source) {
        this.data = Arrays.copyOf(source, 2);
    }

    public byte[] data() {
        return data;
    }

    // WS-STKEXTR-STATUS
    public String get_WS_STKEXTR_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 2);
    }
    public void set_WS_STKEXTR_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 2, value);
    }
}
