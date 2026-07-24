package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード ORD1-受注レコード を逐語対訳した自動生成コード。 */
public final class ORD1_受注レコード {

    /** 01 ORD1-受注レコード 総バイト長 253。 */
    public static final int LENGTH = 253;

    private final byte[] data;

    public ORD1_受注レコード() {
        this.data = new byte[253];
    }

    public ORD1_受注レコード(byte[] source) {
        this.data = Arrays.copyOf(source, 253);
    }

    public byte[] data() {
        return data;
    }

    // ORD1-受注番号
    public String get_ORD1_受注番号() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 10);
    }
    public void set_ORD1_受注番号(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 10, value);
    }

    // ORD1-受注日
    public long get_ORD1_受注日() {
        return CobolRuntime.decodeZoned(data, 10, 8);
    }
    public void set_ORD1_受注日(long value) {
        CobolRuntime.encodeZoned(data, 10, 8, false, value);
    }

    // ORD1-受注日-YMD REDEFINES ORD1-受注日 (同一オフセット 10)

    // ORD1-受注日-年
    public long get_ORD1_受注日_年() {
        return CobolRuntime.decodeZoned(data, 10, 4);
    }
    public void set_ORD1_受注日_年(long value) {
        CobolRuntime.encodeZoned(data, 10, 4, false, value);
    }

    // ORD1-受注日-月
    public long get_ORD1_受注日_月() {
        return CobolRuntime.decodeZoned(data, 14, 2);
    }
    public void set_ORD1_受注日_月(long value) {
        CobolRuntime.encodeZoned(data, 14, 2, false, value);
    }

    // ORD1-受注日-日
    public long get_ORD1_受注日_日() {
        return CobolRuntime.decodeZoned(data, 16, 2);
    }
    public void set_ORD1_受注日_日(long value) {
        CobolRuntime.encodeZoned(data, 16, 2, false, value);
    }

    // ORD1-得意先コード
    public String get_ORD1_得意先コード() {
        return CobolRuntime.decodeAlphanumeric(data, 18, 6);
    }
    public void set_ORD1_得意先コード(String value) {
        CobolRuntime.encodeAlphanumeric(data, 18, 6, value);
    }

    // ORD1-受注金額合計
    public long get_ORD1_受注金額合計() {
        return CobolRuntime.decodePacked(data, 24, 6);
    }
    public void set_ORD1_受注金額合計(long value) {
        CobolRuntime.encodePacked(data, 24, 6, true, value);
    }

    // ORD1-明細件数
    public long get_ORD1_明細件数() {
        return CobolRuntime.decodePacked(data, 30, 2);
    }
    public void set_ORD1_明細件数(long value) {
        CobolRuntime.encodePacked(data, 30, 2, true, value);
    }

    // ORD1-明細行 OCCURS 10 TIMES (要素長 22, offset 32)

    // ORD1-商品コード
    public String get_ORD1_商品コード(int i0) {
        return CobolRuntime.decodeAlphanumeric(data, 32 + i0 * 22, 8);
    }
    public void set_ORD1_商品コード(int i0, String value) {
        CobolRuntime.encodeAlphanumeric(data, 32 + i0 * 22, 8, value);
    }

    // ORD1-数量
    public long get_ORD1_数量(int i0) {
        return CobolRuntime.decodePacked(data, 40 + i0 * 22, 3);
    }
    public void set_ORD1_数量(int i0, long value) {
        CobolRuntime.encodePacked(data, 40 + i0 * 22, 3, true, value);
    }

    // ORD1-単価
    public long get_ORD1_単価(int i0) {
        return CobolRuntime.decodePacked(data, 43 + i0 * 22, 5);
    }
    public void set_ORD1_単価(int i0, long value) {
        CobolRuntime.encodePacked(data, 43 + i0 * 22, 5, true, value);
    }

    // ORD1-金額
    public long get_ORD1_金額(int i0) {
        return CobolRuntime.decodePacked(data, 48 + i0 * 22, 6);
    }
    public void set_ORD1_金額(int i0, long value) {
        CobolRuntime.encodePacked(data, 48 + i0 * 22, 6, true, value);
    }

    // ORD1-処理区分
    public String get_ORD1_処理区分() {
        return CobolRuntime.decodeAlphanumeric(data, 252, 1);
    }
    public void set_ORD1_処理区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 252, 1, value);
    }

    public boolean is_ORD1_新規登録() {
        return get_ORD1_処理区分().equals("1");
    }

    public boolean is_ORD1_訂正() {
        return get_ORD1_処理区分().equals("2");
    }

    public boolean is_ORD1_取消() {
        return get_ORD1_処理区分().equals("9");
    }
}
