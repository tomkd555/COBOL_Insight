package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK005 のデータ部レコード LK-メッセージ区分 を逐語対訳した自動生成コード。 */
public final class LK_メッセージ区分 {

    /** 01 LK-メッセージ区分 総バイト長 2。 */
    public static final int LENGTH = 2;

    private final byte[] data;

    public LK_メッセージ区分() {
        this.data = new byte[2];
    }

    public LK_メッセージ区分(byte[] source) {
        this.data = Arrays.copyOf(source, 2);
    }

    public byte[] data() {
        return data;
    }

    // LK-メッセージ区分
    public String get_LK_メッセージ区分() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 2);
    }
    public void set_LK_メッセージ区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 2, value);
    }
}
