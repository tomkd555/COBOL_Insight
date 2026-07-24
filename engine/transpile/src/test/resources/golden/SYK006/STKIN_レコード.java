package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK006 のデータ部レコード STKIN-レコード を逐語対訳した自動生成コード。 */
public final class STKIN_レコード {

    /** 01 STKIN-レコード 総バイト長 17。 */
    public static final int LENGTH = 17;

    private final byte[] data;

    public STKIN_レコード() {
        this.data = new byte[17];
    }

    public STKIN_レコード(byte[] source) {
        this.data = Arrays.copyOf(source, 17);
    }

    public byte[] data() {
        return data;
    }

    // STKIN-商品コード
    public String get_STKIN_商品コード() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_STKIN_商品コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }

    // STKIN-倉庫コード
    public String get_STKIN_倉庫コード() {
        return CobolRuntime.decodeAlphanumeric(data, 8, 4);
    }
    public void set_STKIN_倉庫コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 8, 4, value);
    }

    // STKIN-増減数量
    public long get_STKIN_増減数量() {
        return CobolRuntime.decodePacked(data, 12, 4);
    }
    public void set_STKIN_増減数量(long value) {
        CobolRuntime.encodePacked(data, 12, 4, true, value);
    }

    // STKIN-更新区分
    public String get_STKIN_更新区分() {
        return CobolRuntime.decodeAlphanumeric(data, 16, 1);
    }
    public void set_STKIN_更新区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 16, 1, value);
    }

    public boolean is_STKIN_増加() {
        return get_STKIN_更新区分().equals("1");
    }

    public boolean is_STKIN_減少() {
        return get_STKIN_更新区分().equals("2");
    }
}
