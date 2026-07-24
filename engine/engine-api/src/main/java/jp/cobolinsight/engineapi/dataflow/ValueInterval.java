package jp.cobolinsight.engineapi.dataflow;

/**
 * 整数区間 [lo, hi]。片側または両側が非有界の場合は該当フラグを立てる。非有界側の lo/hi
 * フィールドは意味を持たない。区間値域解析(R005/R028)がノード入口の変数の取り得る値を表す。
 */
public record ValueInterval(long lo, long hi, boolean loUnbounded, boolean hiUnbounded) {

    public ValueInterval {
        if (!loUnbounded && !hiUnbounded && lo > hi) {
            throw new IllegalArgumentException("lo must be <= hi: " + lo + ".." + hi);
        }
    }

    /** 有界区間 [lo, hi]。 */
    public static ValueInterval of(long lo, long hi) {
        return new ValueInterval(lo, hi, false, false);
    }

    /** 単一値 {v}。 */
    public static ValueInterval point(long v) {
        return new ValueInterval(v, v, false, false);
    }

    /** 両側非有界 (-∞, +∞)。 */
    public static ValueInterval unbounded() {
        return new ValueInterval(0L, 0L, true, true);
    }

    /** 区間の上端が max を超え得るか。 */
    public boolean mayExceed(long max) {
        return hiUnbounded || hi > max;
    }

    /** 区間が 0 以下の値を含み得るか。 */
    public boolean mayBeNonPositive() {
        return loUnbounded || lo <= 0L;
    }

    /** 区間が負の値を含み得るか。 */
    public boolean mayBeNegative() {
        return loUnbounded || lo < 0L;
    }

    /** v が区間に含まれるか。 */
    public boolean contains(long v) {
        return (loUnbounded || lo <= v) && (hiUnbounded || v <= hi);
    }
}
