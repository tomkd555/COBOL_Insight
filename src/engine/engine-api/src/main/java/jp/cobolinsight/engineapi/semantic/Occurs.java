package jp.cobolinsight.engineapi.semantic;

import java.util.Objects;
import java.util.Optional;

/** OCCURS句。固定回数は minTimes == maxTimes。可変長は DEPENDING ON の変数名を持つ。 */
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
