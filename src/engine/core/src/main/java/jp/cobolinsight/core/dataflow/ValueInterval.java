package jp.cobolinsight.core.dataflow;

/**
 * Integer interval [lo, hi]. If one or both sides are unbounded, the corresponding flag is set.
 * The lo/hi field on an unbounded side is meaningless. Interval analysis (R005/R028) uses this
 * to represent the possible values of a variable at a node's entry.
 */
public record ValueInterval(long lo, long hi, boolean loUnbounded, boolean hiUnbounded) {

    public ValueInterval {
        if (!loUnbounded && !hiUnbounded && lo > hi) {
            throw new IllegalArgumentException("lo must be <= hi: " + lo + ".." + hi);
        }
    }

    /** Bounded interval [lo, hi]. */
    public static ValueInterval of(long lo, long hi) {
        return new ValueInterval(lo, hi, false, false);
    }

    /** Single value {v}. */
    public static ValueInterval point(long v) {
        return new ValueInterval(v, v, false, false);
    }

    /** Unbounded on both sides (-∞, +∞). */
    public static ValueInterval unbounded() {
        return new ValueInterval(0L, 0L, true, true);
    }

    /** Whether the interval's upper end may exceed max. */
    public boolean mayExceed(long max) {
        return hiUnbounded || hi > max;
    }

    /** Whether the interval may contain a value of 0 or less. */
    public boolean mayBeNonPositive() {
        return loUnbounded || lo <= 0L;
    }

    /** Whether the interval may contain a negative value. */
    public boolean mayBeNegative() {
        return loUnbounded || lo < 0L;
    }

    /** Whether v is contained in the interval. */
    public boolean contains(long v) {
        return (loUnbounded || lo <= v) && (hiUnbounded || v <= hi);
    }
}
