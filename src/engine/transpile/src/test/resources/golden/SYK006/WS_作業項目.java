package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK006 のデータ部レコード WS-作業項目 を逐語対訳した自動生成コード。 */
public final class WS_作業項目 {

    /** 01 WS-作業項目 総バイト長 94。 */
    public static final int LENGTH = 94;

    private final byte[] data;

    public WS_作業項目() {
        this.data = new byte[94];
    }

    public WS_作業項目(byte[] source) {
        this.data = Arrays.copyOf(source, 94);
    }

    public byte[] data() {
        return data;
    }

    // WS-処理件数
    public long get_WS_処理件数() {
        return CobolRuntime.decodeZoned(data, 0, 5);
    }
    public void set_WS_処理件数(long value) {
        CobolRuntime.encodeZoned(data, 0, 5, false, value);
    }

    // WS-エラー件数
    public long get_WS_エラー件数() {
        return CobolRuntime.decodeZoned(data, 5, 5);
    }
    public void set_WS_エラー件数(long value) {
        CobolRuntime.encodeZoned(data, 5, 5, false, value);
    }

    // WS-エラー件数INDEX
    public long get_WS_エラー件数INDEX() {
        return CobolRuntime.decodeBinary(data, 10, 2, true);
    }
    public void set_WS_エラー件数INDEX(long value) {
        CobolRuntime.encodeBinary(data, 10, 2, true, value);
    }

    // WS-メッセージ区分
    public String get_WS_メッセージ区分() {
        return CobolRuntime.decodeAlphanumeric(data, 12, 2);
    }
    public void set_WS_メッセージ区分(String value) {
        CobolRuntime.encodeAlphanumeric(data, 12, 2, value);
    }

    // WS-メッセージ内容
    public String get_WS_メッセージ内容() {
        return CobolRuntime.decodeAlphanumeric(data, 14, 80);
    }
    public void set_WS_メッセージ内容(String value) {
        CobolRuntime.encodeAlphanumeric(data, 14, 80, value);
    }
}
