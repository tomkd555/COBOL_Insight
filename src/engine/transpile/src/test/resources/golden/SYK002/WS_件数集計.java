package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK002 のデータ部レコード WS-件数集計 を逐語対訳した自動生成コード。 */
public final class WS_件数集計 {

    /** 01 WS-件数集計 総バイト長 10。 */
    public static final int LENGTH = 10;

    private final byte[] data;

    public WS_件数集計() {
        this.data = new byte[10];
    }

    public WS_件数集計(byte[] source) {
        this.data = Arrays.copyOf(source, 10);
    }

    public byte[] data() {
        return data;
    }

    // WS-新規件数
    public long get_WS_新規件数() {
        return CobolRuntime.decodeZoned(data, 0, 5);
    }
    public void set_WS_新規件数(long value) {
        CobolRuntime.encodeZoned(data, 0, 5, false, value);
    }

    // WS-更新件数
    public long get_WS_更新件数() {
        return CobolRuntime.decodeZoned(data, 5, 5);
    }
    public void set_WS_更新件数(long value) {
        CobolRuntime.encodeZoned(data, 5, 5, false, value);
    }
}
