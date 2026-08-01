package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード HOST-倉庫コード を逐語対訳した自動生成コード。 */
public final class HOST_倉庫コード {

    /** 01 HOST-倉庫コード 総バイト長 4。 */
    public static final int LENGTH = 4;

    private final byte[] data;

    public HOST_倉庫コード() {
        this.data = new byte[4];
    }

    public HOST_倉庫コード(byte[] source) {
        this.data = Arrays.copyOf(source, 4);
    }

    public byte[] data() {
        return data;
    }

    // HOST-倉庫コード
    public String get_HOST_倉庫コード() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 4);
    }
    public void set_HOST_倉庫コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 4, value);
    }
}
