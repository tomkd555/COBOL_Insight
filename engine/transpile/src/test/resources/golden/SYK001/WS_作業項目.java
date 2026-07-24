package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK001 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 37。 */
    public static final int LENGTH = 37;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[37];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 37);
    }

    public byte[] data() {
        return data;
    }

    // WS-IDX
    public long get_WS_IDX() {
        return CobolRuntime.decodeBinary(data, 0, 2, true);
    }
    public void set_WS_IDX(long value) {
        CobolRuntime.encodeBinary(data, 0, 2, true, value);
    }

    // WS-検証金額
    public long get_WS_検証金額() {
        return CobolRuntime.decodePacked(data, 2, 6);
    }
    public void set_WS_検証金額(long value) {
        CobolRuntime.encodePacked(data, 2, 6, true, value);
    }

    // WS-上限金額
    public long get_WS_上限金額() {
        return CobolRuntime.decodePacked(data, 8, 6);
    }
    public void set_WS_上限金額(long value) {
        CobolRuntime.encodePacked(data, 8, 6, true, value);
    }

    // WS-印字用金額
    public long get_WS_印字用金額() {
        return CobolRuntime.decodeZoned(data, 14, 6);
    }
    public void set_WS_印字用金額(long value) {
        CobolRuntime.encodeZoned(data, 14, 6, false, value);
    }

    // WS-合計チェック
    public long get_WS_合計チェック() {
        return CobolRuntime.decodePacked(data, 20, 6);
    }
    public void set_WS_合計チェック(long value) {
        CobolRuntime.encodePacked(data, 20, 6, true, value);
    }

    // WS-エラー件数
    public long get_WS_エラー件数() {
        return CobolRuntime.decodeZoned(data, 26, 5);
    }
    public void set_WS_エラー件数(long value) {
        CobolRuntime.encodeZoned(data, 26, 5, false, value);
    }

    // WS-処理件数
    public long get_WS_処理件数() {
        return CobolRuntime.decodeZoned(data, 31, 5);
    }
    public void set_WS_処理件数(long value) {
        CobolRuntime.encodeZoned(data, 31, 5, false, value);
    }

    // WS-チェック結果
    public String get_WS_チェック結果() {
        return CobolRuntime.decodeAlphanumeric(data, 36, 1);
    }
    public void set_WS_チェック結果(String value) {
        CobolRuntime.encodeAlphanumeric(data, 36, 1, value);
    }
}
