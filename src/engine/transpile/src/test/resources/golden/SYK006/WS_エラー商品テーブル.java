package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK006 のデータ部レコード WS-エラー商品テーブル を逐語対訳した自動生成コード。 */
public final class WS_エラー商品テーブル {

    /** 01 WS-エラー商品テーブル 総バイト長 160。 */
    public static final int LENGTH = 160;

    private final byte[] data;

    public WS_エラー商品テーブル() {
        this.data = new byte[160];
    }

    public WS_エラー商品テーブル(byte[] source) {
        this.data = Arrays.copyOf(source, 160);
    }

    public byte[] data() {
        return data;
    }

    // WS-エラー商品
    public String get_WS_エラー商品() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_WS_エラー商品(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }
}
