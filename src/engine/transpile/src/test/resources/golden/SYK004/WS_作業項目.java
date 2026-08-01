package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK004 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 9。 */
    public static final int LENGTH = 9;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[9];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 9);
    }

    public byte[] data() {
        return data;
    }

    // WS-在庫残数
    public long get_WS_在庫残数() {
        return CobolRuntime.decodePacked(data, 0, 4);
    }
    public void set_WS_在庫残数(long value) {
        CobolRuntime.encodePacked(data, 0, 4, true, value);
    }

    // WS-引当数量
    public long get_WS_引当数量() {
        return CobolRuntime.decodePacked(data, 4, 4);
    }
    public void set_WS_引当数量(long value) {
        CobolRuntime.encodePacked(data, 4, 4, true, value);
    }

    // WS-判定区分
    public String get_WS_判定区分() {
        return CobolRuntime.decodeAlphanumeric(data, 8, 1);
    }
    public void set_WS_判定区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 8, 1, value);
    }

    public boolean is_WS_在庫あり() {
        return get_WS_判定区分().equals("1");
    }

    public boolean is_WS_在庫なし() {
        return get_WS_判定区分().equals("2");
    }
}
