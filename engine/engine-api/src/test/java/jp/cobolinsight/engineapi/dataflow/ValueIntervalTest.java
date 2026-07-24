package jp.cobolinsight.engineapi.dataflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueIntervalTest {

    @Test
    void ofRejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> ValueInterval.of(5, 3));
    }

    @Test
    void pointContainsOnlyItsValue() {
        ValueInterval p = ValueInterval.point(7);
        assertTrue(p.contains(7));
        assertFalse(p.contains(6));
        assertFalse(p.contains(8));
    }

    @Test
    void mayExceedRespectsUpperBound() {
        ValueInterval bounded = ValueInterval.of(1, 10);
        assertFalse(bounded.mayExceed(10));
        assertTrue(bounded.mayExceed(9));
    }

    @Test
    void upperUnboundedAlwaysMayExceed() {
        ValueInterval counter = new ValueInterval(0, 0, false, true);
        assertTrue(counter.mayExceed(20));
        assertFalse(counter.mayBeNegative());
        assertTrue(counter.mayBeNonPositive());
    }

    @Test
    void negativeAndNonPositiveOnBoundedInterval() {
        ValueInterval range = ValueInterval.of(-3, 4);
        assertTrue(range.mayBeNegative());
        assertTrue(range.mayBeNonPositive());
        assertFalse(ValueInterval.of(1, 4).mayBeNegative());
        assertFalse(ValueInterval.of(1, 4).mayBeNonPositive());
    }

    @Test
    void unboundedContainsEverything() {
        ValueInterval any = ValueInterval.unbounded();
        assertTrue(any.contains(Long.MIN_VALUE));
        assertTrue(any.contains(0));
        assertTrue(any.contains(Long.MAX_VALUE));
        assertTrue(any.mayExceed(Long.MAX_VALUE));
        assertTrue(any.mayBeNegative());
    }

    @Test
    void containsRespectsBothBounds() {
        ValueInterval range = ValueInterval.of(1, 10);
        assertEquals(true, range.contains(1));
        assertEquals(true, range.contains(10));
        assertFalse(range.contains(0));
        assertFalse(range.contains(11));
    }
}
