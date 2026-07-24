package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード WS-エラーメッセージ を逐語対訳した自動生成コード。 */
public final class WS_エラーメッセージ {

    /** 01 WS-エラーメッセージ 総バイト長 40。 */
    public static final int LENGTH = 40;

    private final byte[] data;

    public WS_エラーメッセージ() {
        this.data = new byte[40];
    }

    public WS_エラーメッセージ(byte[] source) {
        this.data = Arrays.copyOf(source, 40);
    }

    public byte[] data() {
        return data;
    }

    // WS-エラーメッセージ
    public String get_WS_エラーメッセージ() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 40);
    }
    public void set_WS_エラーメッセージ(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 40, value);
    }
}
