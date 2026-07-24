package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK008 のデータ部レコード WS-受注入力マップ を逐語対訳した自動生成コード。 */
public final class WS_受注入力マップ {

    /** 01 WS-受注入力マップ 総バイト長 48。 */
    public static final int LENGTH = 48;

    private final byte[] data;

    public WS_受注入力マップ() {
        this.data = new byte[48];
    }

    public WS_受注入力マップ(byte[] source) {
        this.data = Arrays.copyOf(source, 48);
    }

    public byte[] data() {
        return data;
    }

    // WS-ORDNO-入力
    public String get_WS_ORDNO_入力() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_WS_ORDNO_入力(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }

    // WS-MSG-出力
    public String get_WS_MSG_出力() {
        return CobolRuntime.decodeAlphanumeric(data, 8, 40);
    }
    public void set_WS_MSG_出力(String value) {
        CobolRuntime.encodeAlphanumeric(data, 8, 40, value);
    }
}
