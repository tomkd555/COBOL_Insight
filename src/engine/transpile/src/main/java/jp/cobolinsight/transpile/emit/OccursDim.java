package jp.cobolinsight.transpile.emit;

/**
 * One dimension of an OCCURS group. count is the repetition count; stride is the byte length of one
 * occurrence (the spacing between element starts). For nested OCCURS, dimensions map to accessor
 * index arguments from outermost to innermost.
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
