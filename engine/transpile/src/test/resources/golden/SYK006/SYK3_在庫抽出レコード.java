package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK006 のデータ部レコード SYK3-在庫抽出レコード を逐語対訳した自動生成コード。 */
public final class SYK3_在庫抽出レコード {

    /** 01 SYK3-在庫抽出レコード 総バイト長 29。 */
    public static final int LENGTH = 29;

    private final byte[] data;

    public SYK3_在庫抽出レコード() {
        this.data = new byte[29];
    }

    public SYK3_在庫抽出レコード(byte[] source) {
        this.data = Arrays.copyOf(source, 29);
    }

    public byte[] data() {
        return data;
    }

    // SYK3-商品コード
    public String get_SYK3_商品コード() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 8);
    }
    public void set_SYK3_商品コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 8, value);
    }

    // SYK3-倉庫コード
    public String get_SYK3_倉庫コード() {
        return CobolRuntime.decodeAlphanumeric(data, 8, 4);
    }
    public void set_SYK3_倉庫コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 8, 4, value);
    }

    // SYK3-在庫数量
    public long get_SYK3_在庫数量() {
        return CobolRuntime.decodePacked(data, 12, 4);
    }
    public void set_SYK3_在庫数量(long value) {
        CobolRuntime.encodePacked(data, 12, 4, true, value);
    }

    // SYK3-引当可能数量
    public long get_SYK3_引当可能数量() {
        return CobolRuntime.decodePacked(data, 16, 4);
    }
    public void set_SYK3_引当可能数量(long value) {
        CobolRuntime.encodePacked(data, 16, 4, true, value);
    }

    // SYK3-更新区分
    public String get_SYK3_更新区分() {
        return CobolRuntime.decodeAlphanumeric(data, 20, 1);
    }
    public void set_SYK3_更新区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 20, 1, value);
    }

    public boolean is_SYK3_増加() {
        return get_SYK3_更新区分().equals("1");
    }

    public boolean is_SYK3_減少() {
        return get_SYK3_更新区分().equals("2");
    }

    // SYK3-処理日
    public long get_SYK3_処理日() {
        return CobolRuntime.decodeZoned(data, 21, 8);
    }
    public void set_SYK3_処理日(long value) {
        CobolRuntime.encodeZoned(data, 21, 8, false, value);
    }
}
