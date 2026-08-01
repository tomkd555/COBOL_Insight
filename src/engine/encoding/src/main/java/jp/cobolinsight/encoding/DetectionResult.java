package jp.cobolinsight.encoding;

/**
 * 文字コード判別の結果。
 *
 * @param codePage    判別または推定したコードページ
 * @param confidence  確信度(0〜100)
 * @param soSiPresent SO/SI(0x0E/0x0F)を含むか
 * @param estimated   内容判別ではない推定か(EBCDIC推定・フォールバック)
 */
public record DetectionResult(CodePage codePage, int confidence, boolean soSiPresent, boolean estimated) {
}
