package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK005 のデータ部レコード LK-メッセージ内容 を逐語対訳した自動生成コード。 */
public final class LK_メッセージ内容 {

    /** 01 LK-メッセージ内容 総バイト長 80。 */
    public static final int LENGTH = 80;

    private final byte[] data;

    public LK_メッセージ内容() {
        this.data = new byte[80];
    }

    public LK_メッセージ内容(byte[] source) {
        this.data = Arrays.copyOf(source, 80);
    }

    public byte[] data() {
        return data;
    }

    // LK-メッセージ内容
    public String get_LK_メッセージ内容() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 80);
    }
    public void set_LK_メッセージ内容(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 80, value);
    }
}
