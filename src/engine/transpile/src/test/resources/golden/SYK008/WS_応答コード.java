package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK008 のデータ部レコード WS-応答コード を逐語対訳した自動生成コード。 */
public final class WS_応答コード {

    /** 01 WS-応答コード 総バイト長 8。 */
    public static final int LENGTH = 8;

    private final byte[] data;

    public WS_応答コード() {
        this.data = new byte[8];
    }

    public WS_応答コード(byte[] source) {
        this.data = Arrays.copyOf(source, 8);
    }

    public byte[] data() {
        return data;
    }

    // WS-RESPコード
    public long get_WS_RESPコード() {
        return CobolRuntime.decodeBinary(data, 0, 4, true);
    }
    public void set_WS_RESPコード(long value) {
        CobolRuntime.encodeBinary(data, 0, 4, true, value);
    }

    // WS-RESP2コード
    public long get_WS_RESP2コード() {
        return CobolRuntime.decodeBinary(data, 4, 4, true);
    }
    public void set_WS_RESP2コード(long value) {
        CobolRuntime.encodeBinary(data, 4, 4, true, value);
    }
}
