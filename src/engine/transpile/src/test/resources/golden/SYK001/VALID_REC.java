package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード VALID-REC を逐語対訳した自動生成コード。 */
public final class VALID_REC {

    /** 01 VALID-REC 総バイト長 253。 */
    public static final int LENGTH = 253;

    private final byte[] data;

    public VALID_REC() {
        this.data = new byte[253];
    }

    public VALID_REC(byte[] source) {
        this.data = Arrays.copyOf(source, 253);
    }

    public byte[] data() {
        return data;
    }

    // VALID-REC
    public String get_VALID_REC() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 253);
    }
    public void set_VALID_REC(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 253, value);
    }
}
