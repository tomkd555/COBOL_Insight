package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK004 のデータ部レコード LK-要求数量 を逐語対訳した自動生成コード。 */
public final class LK_要求数量 {

    /** 01 LK-要求数量 総バイト長 3。 */
    public static final int LENGTH = 3;

    private final byte[] data;

    public LK_要求数量() {
        this.data = new byte[3];
    }

    public LK_要求数量(byte[] source) {
        this.data = Arrays.copyOf(source, 3);
    }

    public byte[] data() {
        return data;
    }

    // LK-要求数量
    public long get_LK_要求数量() {
        return CobolRuntime.decodePacked(data, 0, 3);
    }
    public void set_LK_要求数量(long value) {
        CobolRuntime.encodePacked(data, 0, 3, true, value);
    }
}
