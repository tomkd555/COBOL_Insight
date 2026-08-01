package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK005 のデータ部レコード WS-編集メッセージ を逐語対訳した自動生成コード。 */
public final class WS_編集メッセージ {

    /** 01 WS-編集メッセージ 総バイト長 100。 */
    public static final int LENGTH = 100;

    private final byte[] data;

    public WS_編集メッセージ() {
        this.data = new byte[100];
    }

    public WS_編集メッセージ(byte[] source) {
        this.data = Arrays.copyOf(source, 100);
    }

    public byte[] data() {
        return data;
    }

    // WS-編集メッセージ
    public String get_WS_編集メッセージ() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 100);
    }
    public void set_WS_編集メッセージ(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 100, value);
    }
}
