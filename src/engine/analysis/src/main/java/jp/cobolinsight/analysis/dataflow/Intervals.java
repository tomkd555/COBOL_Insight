package jp.cobolinsight.analysis.dataflow;

import jp.cobolinsight.core.dataflow.ValueInterval;

/**
 * Interval arithmetic, join (hull), widening, and condition-based tightening (narrowing) over
 * {@link ValueInterval}. An unbounded end is propagated through arithmetic and widening; on
 * overflow, addition/subtraction/multiplication fall the affected end back to unbounded.
 * Division does not track precision and always falls back to unbounded on both sides.
 */
final class Intervals {

    static final ValueInterval UNBOUNDED = ValueInterval.unbounded();

    private Intervals() {
    }

    /** Joins two intervals (the smallest enclosing interval). Any end that is unbounded on either side keeps the result unbounded there too. */
    static ValueInterval hull(ValueInterval x, ValueInterval y) {
        Long lo = (x.loUnbounded() || y.loUnbounded()) ? null : Math.min(x.lo(), y.lo());
        Long hi = (x.hiUnbounded() || y.hiUnbounded()) ? null : Math.max(x.hi(), y.hi());
        return make(lo, hi);
    }

    /**
     * Interval widening. Relative to {@code old}, jumps the corresponding end to unbounded only
     * when {@code next} goes below the lower end or above the upper end. This cuts off an
     * infinite ascending chain after finitely many steps and converges the fixed-point
     * computation.
     */
    static ValueInterval widen(ValueInterval old, ValueInterval next) {
        boolean loU;
        long loVal = 0;
        if (old.loUnbounded()) {
            loU = true;
        } else if (next.loUnbounded() || next.lo() < old.lo()) {
            loU = true;
        } else {
            loU = false;
            loVal = old.lo();
        }
        boolean hiU;
        long hiVal = 0;
        if (old.hiUnbounded()) {
            hiU = true;
        } else if (next.hiUnbounded() || next.hi() > old.hi()) {
            hiU = true;
        } else {
            hiU = false;
            hiVal = old.hi();
        }
        return new ValueInterval(loVal, hiVal, loU, hiU);
    }

    static ValueInterval add(ValueInterval x, ValueInterval y) {
        Long lo = (x.loUnbounded() || y.loUnbounded()) ? null : satAdd(x.lo(), y.lo());
        Long hi = (x.hiUnbounded() || y.hiUnbounded()) ? null : satAdd(x.hi(), y.hi());
        return make(lo, hi);
    }

    static ValueInterval sub(ValueInterval x, ValueInterval y) {
        Long lo = (x.loUnbounded() || y.hiUnbounded()) ? null : satSub(x.lo(), y.hi());
        Long hi = (x.hiUnbounded() || y.loUnbounded()) ? null : satSub(x.hi(), y.lo());
        return make(lo, hi);
    }

    static ValueInterval mul(ValueInterval x, ValueInterval y) {
        if (isPointZero(x) || isPointZero(y)) {
            return ValueInterval.point(0);
        }
        if (x.loUnbounded() || x.hiUnbounded() || y.loUnbounded() || y.hiUnbounded()) {
            return UNBOUNDED;
        }
        long[] xs = {x.lo(), x.hi()};
        long[] ys = {y.lo(), y.hi()};
        Long min = null;
        Long max = null;
        for (long a : xs) {
            for (long b : ys) {
                Long p = satMul(a, b);
                if (p == null) {
                    return UNBOUNDED;
                }
                if (min == null || p < min) {
                    min = p;
                }
                if (max == null || p > max) {
                    max = p;
                }
            }
        }
        return make(min, max);
    }

    /** Division does not track interval precision and falls back to unbounded on both sides. */
    static ValueInterval div(ValueInterval x, ValueInterval y) {
        return UNBOUNDED;
    }

    /** Tightens the upper end to at most bound (the lower end is unchanged). A contradiction (lower end > bound) is rounded to the point interval [bound,bound]. */
    static ValueInterval capHi(ValueInterval v, long bound) {
        long newHi = v.hiUnbounded() ? bound : Math.min(v.hi(), bound);
        if (!v.loUnbounded() && v.lo() > newHi) {
            return ValueInterval.point(newHi);
        }
        return new ValueInterval(v.loUnbounded() ? 0 : v.lo(), newHi, v.loUnbounded(), false);
    }

    /** Tightens the lower end to at least bound (the upper end is unchanged). A contradiction (bound > upper end) is rounded to the point interval [bound,bound]. */
    static ValueInterval capLo(ValueInterval v, long bound) {
        long newLo = v.loUnbounded() ? bound : Math.max(v.lo(), bound);
        if (!v.hiUnbounded() && newLo > v.hi()) {
            return ValueInterval.point(newLo);
        }
        return new ValueInterval(newLo, v.hiUnbounded() ? 0 : v.hi(), false, v.hiUnbounded());
    }

    /** Builds an interval from its ends (null = unbounded). If both ends are bounded and lo>hi is a contradiction, it is rounded to a point interval, favoring the upper end. */
    static ValueInterval make(Long lo, Long hi) {
        boolean lu = lo == null;
        boolean hu = hi == null;
        if (!lu && !hu && lo > hi) {
            return ValueInterval.point(hi);
        }
        return new ValueInterval(lu ? 0 : lo, hu ? 0 : hi, lu, hu);
    }

    private static boolean isPointZero(ValueInterval v) {
        return !v.loUnbounded() && !v.hiUnbounded() && v.lo() == 0 && v.hi() == 0;
    }

    private static Long satAdd(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Long satSub(long a, long b) {
        try {
            return Math.subtractExact(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Long satMul(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return null;
        }
    }
}
