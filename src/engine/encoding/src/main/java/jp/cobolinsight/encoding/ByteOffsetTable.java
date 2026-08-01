package jp.cobolinsight.encoding;

/**
 * 復号後の文字位置と原バイトオフセットの対応表。
 * 行番号は1始まり、行内の文字位置(column)は0始まりのUTF-16文字単位。
 */
public final class ByteOffsetTable {

    /** 長さ charCount+1。末尾要素は原バイト列全体の長さ。 */
    private final int[] charStartByteOffsets;
    /** 各行の先頭文字位置。行は '\n' の直後で始まる。 */
    private final int[] lineStartCharIndexes;

    ByteOffsetTable(String text, int[] charStartByteOffsets) {
        this.charStartByteOffsets = charStartByteOffsets;
        // 末尾の改行は次の行を開かない。改行の後に文字が続く場合だけ行数を増やす。
        int lineCount = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                lineCount++;
            }
        }
        this.lineStartCharIndexes = new int[lineCount];
        int line = 1;
        for (int i = 0; i < text.length() && line < lineCount; i++) {
            if (text.charAt(i) == '\n') {
                lineStartCharIndexes[line++] = i + 1;
            }
        }
    }

    public int charCount() {
        return charStartByteOffsets.length - 1;
    }

    public int byteLength() {
        return charStartByteOffsets[charStartByteOffsets.length - 1];
    }

    /** 文字位置(0〜charCount)に対応する原バイトオフセット。charCountを渡すと全バイト長を返す。 */
    public int byteOffsetOfChar(int charIndex) {
        return charStartByteOffsets[charIndex];
    }

    public int lineCount() {
        return lineStartCharIndexes.length;
    }

    /**
     * 指定行(1始まり)の先頭文字位置。{@code lineCount()+1} は最終行の直後、すなわち本文の末尾を
     * 指す。末尾への挿入点を(行,桁)で表せるようにするためである。
     */
    public int lineStartCharIndex(int line) {
        if (line == lineStartCharIndexes.length + 1) {
            return charCount();
        }
        return lineStartCharIndexes[line - 1];
    }

    /** 指定行(1始まり)の先頭の原バイトオフセット。 */
    public int lineStartByteOffset(int line) {
        return byteOffsetOfChar(lineStartCharIndex(line));
    }

    /** 指定行(1始まり)・行内文字位置(0始まり)の原バイトオフセット。 */
    public int byteOffsetAt(int line, int column) {
        return byteOffsetOfChar(lineStartCharIndex(line) + column);
    }
}
