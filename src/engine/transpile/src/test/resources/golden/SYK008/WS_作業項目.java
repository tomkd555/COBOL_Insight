package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK008 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 1。 */
    public static final int LENGTH = 1;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[1];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 1);
    }

    public byte[] data() {
        return data;
    }

    // WS-検査結果
    public String get_WS_検査結果() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 1);
    }
    public void set_WS_検査結果(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 1, value);
    }

    public boolean is_WS_検査エラー() {
        return get_WS_検査結果().equals("E");
    }
}
