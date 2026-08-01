package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK006 のデータ部レコード WS-ファイル状態 を逐語対訳した自動生成コード。 */
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

    // WS-STKIN-STATUS
    public String get_WS_STKIN_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 2);
    }
    public void set_WS_STKIN_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 2, value);
    }

    // WS-STKEXTR-STATUS
    public String get_WS_STKEXTR_STATUS() {
        return CobolRuntime.decodeAlphanumeric(data, 2, 2);
    }
    public void set_WS_STKEXTR_STATUS(String value) {
        CobolRuntime.encodeAlphanumeric(data, 2, 2, value);
    }
}
