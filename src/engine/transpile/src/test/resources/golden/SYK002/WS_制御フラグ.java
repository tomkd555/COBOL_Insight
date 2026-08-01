package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK002 のデータ部レコード WS-制御フラグ を逐語対訳した自動生成コード。 */
public final class WS_制御フラグ {

    /** 01 WS-制御フラグ 総バイト長 2。 */
    public static final int LENGTH = 2;

    private final byte[] data;

    public WS_制御フラグ() {
        this.data = new byte[2];
    }

    public WS_制御フラグ(byte[] source) {
        this.data = Arrays.copyOf(source, 2);
    }

    public byte[] data() {
        return data;
    }

    // WS-EOF-FLAG
    public String get_WS_EOF_FLAG() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 1);
    }
    public void set_WS_EOF_FLAG(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 1, value);
    }

    public boolean is_WS_EOF() {
        return get_WS_EOF_FLAG().equals("Y");
    }

    // WS-マスタ有無
    public String get_WS_マスタ有無() {
        return CobolRuntime.decodeAlphanumeric(data, 1, 1);
    }
    public void set_WS_マスタ有無(String value) {
        CobolRuntime.encodeAlphanumeric(data, 1, 1, value);
    }

    public boolean is_WS_マスタ該当あり() {
        return get_WS_マスタ有無().equals("Y");
    }

    public boolean is_WS_マスタ該当なし() {
        return get_WS_マスタ有無().equals("N");
    }
}
