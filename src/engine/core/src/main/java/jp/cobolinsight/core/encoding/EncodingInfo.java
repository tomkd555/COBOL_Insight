package jp.cobolinsight.core.encoding;

/**
 * 復号に用いたエンコーディング情報。
 *
 * @param codePage       確定コードページ
 * @param confidence     確信度(0〜100。手動指定は100)
 * @param soSiPresent    原バイト列がSO/SI(0x0E/0x0F)を含むか
 * @param manualOverride 手動指定が自動判別を上書きしたか
 */
public record EncodingInfo(CodePage codePage, int confidence, boolean soSiPresent, boolean manualOverride) {
}
