package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード HOST-商品コード を逐語対訳した自動生成コード。 */
public final class HOST_商品コード {

    /** 01 HOST-商品コード 総バイト長 8。 */
    public static final int LENGTH = 8;

    private final byte[] data;

    public HOST_商品コード() {
        this.data = new byte[8];
    }

    public HOST_商品コード(byte[] source) {
        this.data = Arrays.copyOf(source, 8);
    }

    public byte[] data() {
        return data;
    }

    // HOST-商品コード
    public String get_HOST_商品コード() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_HOST_商品コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }
}
