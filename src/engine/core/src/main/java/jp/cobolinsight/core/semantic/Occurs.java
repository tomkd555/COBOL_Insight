package jp.cobolinsight.core.semantic;

import java.util.Objects;
import java.util.Optional;

/** An OCCURS clause. A fixed count has minTimes == maxTimes. A variable length holds the DEPENDING ON variable name. */
public record Occurs(int minTimes, int maxTimes, Optional<String> dependingOn) {

    public Occurs {
        if (minTimes < 0) {
            throw new IllegalArgumentException("minTimes must be >= 0: " + minTimes);
        }
        if (maxTimes < 1) {
            throw new IllegalArgumentException("maxTimes must be >= 1: " + maxTimes);
        }
        if (maxTimes < minTimes) {
            throw new IllegalArgumentException(
                    "maxTimes must be >= minTimes: " + minTimes + ".." + maxTimes);
        }
        Objects.requireNonNull(dependingOn, "dependingOn");
    }
}
