package jp.cobolinsight.transpile.emit;

/**
 * OCCURS 集団の1次元。count は反復回数、stride は1要素分のバイト長(要素の先頭間隔)。
 * 入れ子の OCCURS では外側から内側の順にアクセサの index 引数へ対応づける。
 */
public record OccursDim(int count, int stride) {

    public OccursDim {
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1: " + count);
        }
        if (stride < 0) {
            throw new IllegalArgumentException("stride must be >= 0: " + stride);
        }
    }
}
