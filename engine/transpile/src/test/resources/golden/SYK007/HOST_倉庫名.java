package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード HOST-倉庫名 を逐語対訳した自動生成コード。 */
public final class HOST_倉庫名 {

    /** 01 HOST-倉庫名 総バイト長 20。 */
    public static final int LENGTH = 20;

    private final byte[] data;

    public HOST_倉庫名() {
        this.data = new byte[20];
    }

    public HOST_倉庫名(byte[] source) {
        this.data = Arrays.copyOf(source, 20);
    }

    public byte[] data() {
        return data;
    }

    // HOST-倉庫名
    public String get_HOST_倉庫名() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 20);
    }
    public void set_HOST_倉庫名(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 20, value);
    }
}
