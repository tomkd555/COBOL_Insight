package jp.cobolinsight.dataflow;

import jp.cobolinsight.engineapi.dataflow.ValueInterval;

/**
 * {@link ValueInterval} 上の区間演算・合流(hull)・widening・条件による絞り込み(narrowing)。
 * 非有界端は算術・widening で伝播させ、加減乗はオーバーフロー時に該当端を非有界へ退避する。
 * 除算は精度を追わず常に両側非有界へ退避する。
 */
final class Intervals {

    static final ValueInterval UNBOUNDED = ValueInterval.unbounded();

    private Intervals() {
    }

    /** 区間の合流(包含最小の外接区間)。いずれかが非有界の端は結果も非有界。 */
    static ValueInterval hull(ValueInterval x, ValueInterval y) {
        Long lo = (x.loUnbounded() || y.loUnbounded()) ? null : Math.min(x.lo(), y.lo());
        Long hi = (x.hiUnbounded() || y.hiUnbounded()) ? null : Math.max(x.hi(), y.hi());
        return make(lo, hi);
    }

    /**
     * 区間 widening。{@code old} を基準に、{@code next} が下端で下回る/上端で上回るときだけ当該端を
     * 非有界へ飛ばす。無限昇鎖を有限回で打ち切り不動点計算を収束させる。
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

    /** 除算は区間精度を追わず両側非有界へ退避する。 */
    static ValueInterval div(ValueInterval x, ValueInterval y) {
        return UNBOUNDED;
    }

    /** 上端を bound 以下へ絞る(下端は不変)。矛盾(下端>bound)は点区間 [bound,bound] へ丸める。 */
    static ValueInterval capHi(ValueInterval v, long bound) {
        long newHi = v.hiUnbounded() ? bound : Math.min(v.hi(), bound);
        if (!v.loUnbounded() && v.lo() > newHi) {
            return ValueInterval.point(newHi);
        }
        return new ValueInterval(v.loUnbounded() ? 0 : v.lo(), newHi, v.loUnbounded(), false);
    }

    /** 下端を bound 以上へ絞る(上端は不変)。矛盾(bound>上端)は点区間 [bound,bound] へ丸める。 */
    static ValueInterval capLo(ValueInterval v, long bound) {
        long newLo = v.loUnbounded() ? bound : Math.max(v.lo(), bound);
        if (!v.hiUnbounded() && newLo > v.hi()) {
            return ValueInterval.point(newLo);
        }
        return new ValueInterval(newLo, v.hiUnbounded() ? 0 : v.hi(), false, v.hiUnbounded());
    }

    /** 端(null=非有界)から区間を組む。両端有界で lo>hi の矛盾は上端優先で点区間へ丸める。 */
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
