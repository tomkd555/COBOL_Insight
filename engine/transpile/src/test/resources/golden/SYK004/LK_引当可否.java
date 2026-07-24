package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK004 のデータ部レコード LK-引当可否 を逐語対訳した自動生成コード。 */
public final class LK_引当可否 {

    /** 01 LK-引当可否 総バイト長 1。 */
    public static final int LENGTH = 1;

    private final byte[] data;

    public LK_引当可否() {
        this.data = new byte[1];
    }

    public LK_引当可否(byte[] source) {
        this.data = Arrays.copyOf(source, 1);
    }

    public byte[] data() {
        return data;
    }

    // LK-引当可否
    public String get_LK_引当可否() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 1);
    }
    public void set_LK_引当可否(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 1, value);
    }
}
