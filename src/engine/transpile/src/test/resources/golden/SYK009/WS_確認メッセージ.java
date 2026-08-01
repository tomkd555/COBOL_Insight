package cobolinsight.generated;

import cobolinsight.runtime.CobolRuntime;
import java.util.Arrays;

/** SYK009 のデータ部レコード WS-確認メッセージ を逐語対訳した自動生成コード。 */
public final class WS_確認メッセージ {

    /** 01 WS-確認メッセージ 総バイト長 40。 */
    public static final int LENGTH = 40;

    private final byte[] data;

    public WS_確認メッセージ() {
        this.data = new byte[40];
    }

    public WS_確認メッセージ(byte[] source) {
        this.data = Arrays.copyOf(source, 40);
    }

    public byte[] data() {
        return data;
    }

    // WS-確認メッセージ
    public String get_WS_確認メッセージ() {
        return CobolRuntime.decodeAlphanumeric(data, 0, 40);
    }
    public void set_WS_確認メッセージ(String value) {
        CobolRuntime.encodeAlphanumeric(data, 0, 40, value);
    }
}
