package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK003 のデータ部レコード LK-チェック結果 を逐語対訳した自動生成コード。 */
public final class LK_チェック結果 {

    /** 01 LK-チェック結果 総バイト長 1。 */
    public static final int LENGTH = 1;

    private final byte[] data;

    public LK_チェック結果() {
        this.data = new byte[1];
    }

    public LK_チェック結果(byte[] source) {
        this.data = Arrays.copyOf(source, 1);
    }

    public byte[] data() {
        return data;
    }

    // LK-チェック結果
    public String get_LK_チェック結果() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 1);
    }
    public void set_LK_チェック結果(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 1, value);
    }
}
