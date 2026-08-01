package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK002 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 17。 */
    public static final int LENGTH = 17;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[17];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 17);
    }

    public byte[] data() {
        return data;
    }

    // WS-PROG-NAME
    public String get_WS_PROG_NAME() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_WS_PROG_NAME(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }

    // WS-引当可否
    public String get_WS_引当可否() {
        return CobolRuntime.decodeAlphanumeric(data, 8, 1);
    }
    public void set_WS_引当可否(String value) {
        CobolRuntime.encodeAlphanumeric(data, 8, 1, value);
    }

    // WS-要求数量
    public long get_WS_要求数量() {
        return CobolRuntime.decodePacked(data, 9, 3);
    }
    public void set_WS_要求数量(long value) {
        CobolRuntime.encodePacked(data, 9, 3, true, value);
    }

    // WS-金額集計エリア
    public long get_WS_金額集計エリア() {
        return CobolRuntime.decodeZoned(data, 12, 5);
    }
    public void set_WS_金額集計エリア(long value) {
        CobolRuntime.encodeZoned(data, 12, 5, false, value);
    }
}
