package jp.cobolinsight.encoding;

/**
 * ソース取込の出力。復号済みテキスト(UTF-16正規化)・原バイト列・
 * オフセット表・確定エンコーディング情報を束ねる。
 *
 * @param text          復号済みテキスト(内部UTF-16正規化)
 * @param originalBytes 原バイト列
 * @param offsetTable   復号後文字位置と原バイトオフセットの対応表
 * @param encodingInfo  確定コードページ・確信度・SO/SI有無・手動指定の有無
 */
public record DecodedSource(String text, byte[] originalBytes, ByteOffsetTable offsetTable, EncodingInfo encodingInfo) {
}
