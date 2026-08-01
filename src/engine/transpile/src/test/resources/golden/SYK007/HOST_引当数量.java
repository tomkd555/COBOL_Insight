package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK007 のデータ部レコード HOST-引当数量 を逐語対訳した自動生成コード。 */
public final class HOST_引当数量 {

    /** 01 HOST-引当数量 総バイト長 4。 */
    public static final int LENGTH = 4;

    private final byte[] data;

    public HOST_引当数量() {
        this.data = new byte[4];
    }

    public HOST_引当数量(byte[] source) {
        this.data = Arrays.copyOf(source, 4);
    }

    public byte[] data() {
        return data;
    }

    // HOST-引当数量
    public long get_HOST_引当数量() {
        return CobolRuntime.decodePacked(data, 0, 4);
    }
    public void set_HOST_引当数量(long value) {
        CobolRuntime.encodePacked(data, 0, 4, true, value);
    }
}
