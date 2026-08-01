package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK002 のデータ部レコード SYK2-受注マスタレコード を逐語対訳した自動生成コード。 */
public final class SYK2_受注マスタレコード {

    /** 01 SYK2-受注マスタレコード 総バイト長 79。 */
    public static final int LENGTH = 79;

    private final byte[] data;

    public SYK2_受注マスタレコード() {
        this.data = new byte[79];
    }

    public SYK2_受注マスタレコード(byte[] source) {
        this.data = Arrays.copyOf(source, 79);
    }

    public byte[] data() {
        return data;
    }

    // SYK2-受注番号
    public String get_SYK2_受注番号() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 10);
    }
    public void set_SYK2_受注番号(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 10, value);
    }

    // SYK2-得意先コード
    public String get_SYK2_得意先コード() {
        return CobolRuntime.decodeAlphanumeric(data, 10, 6);
    }
    public void set_SYK2_得意先コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 10, 6, value);
    }

    // SYK2-受注日
    public long get_SYK2_受注日() {
        return CobolRuntime.decodeZoned(data, 16, 8);
    }
    public void set_SYK2_受注日(long value) {
        CobolRuntime.encodeZoned(data, 16, 8, false, value);
    }

    // SYK2-受注金額合計
    public long get_SYK2_受注金額合計() {
        return CobolRuntime.decodePacked(data, 24, 6);
    }
    public void set_SYK2_受注金額合計(long value) {
        CobolRuntime.encodePacked(data, 24, 6, true, value);
    }

    // SYK2-入金状況
    public String get_SYK2_入金状況() {
        return CobolRuntime.decodeAlphanumeric(data, 30, 1);
    }
    public void set_SYK2_入金状況(String value) {
        CobolRuntime.encodeAlphanumeric(data, 30, 1, value);
    }

    public boolean is_SYK2_入金済() {
        return get_SYK2_入金状況().equals("Y");
    }

    public boolean is_SYK2_未入金() {
        return get_SYK2_入金状況().equals("N");
    }

    // SYK2-登録日時
    public String get_SYK2_登録日時() {
        return CobolRuntime.decodeAlphanumeric(data, 31, 14);
    }
    public void set_SYK2_登録日時(String value) {
        CobolRuntime.encodeAlphanumeric(data, 31, 14, value);
    }

    // SYK2-更新日時
    public String get_SYK2_更新日時() {
        return CobolRuntime.decodeAlphanumeric(data, 45, 14);
    }
    public void set_SYK2_更新日時(String value) {
        CobolRuntime.encodeAlphanumeric(data, 45, 14, value);
    }

    // SYK2-予備領域
    public String get_SYK2_予備領域() {
        return CobolRuntime.decodeAlphanumeric(data, 59, 20);
    }
    public void set_SYK2_予備領域(String value) {
        CobolRuntime.encodeAlphanumeric(data, 59, 20, value);
    }
}
