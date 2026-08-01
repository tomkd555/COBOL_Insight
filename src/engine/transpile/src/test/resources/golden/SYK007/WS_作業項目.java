package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 8。 */
    public static final int LENGTH = 8;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[8];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 8);
    }

    public byte[] data() {
        return data;
    }

    // WS-処理件数
    public long get_WS_処理件数() {
        return CobolRuntime.decodeZoned(data, 0, 5);
    }
    public void set_WS_処理件数(long value) {
        CobolRuntime.encodeZoned(data, 0, 5, false, value);
    }

    // WS-引当率
    public long get_WS_引当率() {
        return CobolRuntime.decodePacked(data, 5, 3);
    }
    public void set_WS_引当率(long value) {
        CobolRuntime.encodePacked(data, 5, 3, true, value);
    }
}
