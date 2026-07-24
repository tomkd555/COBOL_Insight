package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK003 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 13。 */
    public static final int LENGTH = 13;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[13];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 13);
    }

    public byte[] data() {
        return data;
    }

    // WS-I
    public long get_WS_I() {
        return CobolRuntime.decodeBinary(data, 0, 2, true);
    }
    public void set_WS_I(long value) {
        CobolRuntime.encodeBinary(data, 0, 2, true, value);
    }

    // WS-合計
    public long get_WS_合計() {
        return CobolRuntime.decodePacked(data, 2, 6);
    }
    public void set_WS_合計(long value) {
        CobolRuntime.encodePacked(data, 2, 6, true, value);
    }

    // WS-旧チェック方式件数
    public long get_WS_旧チェック方式件数() {
        return CobolRuntime.decodeZoned(data, 8, 5);
    }
    public void set_WS_旧チェック方式件数(long value) {
        CobolRuntime.encodeZoned(data, 8, 5, false, value);
    }
}
