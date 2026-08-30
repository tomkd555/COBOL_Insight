package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;

import java.util.Objects;
import java.util.Optional;

/**
 * A parse result. The type distinguishes success from failure. A single file's failure does not
 * stop the analysis as a whole; it is recorded as an error-level finding and the rest of the
 * analysis continues.
 */
public sealed interface ParseOutcome<T> permits ParseOutcome.Success, ParseOutcome.Failure {

    record Success<T>(T parsedValue) implements ParseOutcome<T> {
        public Success {
            Objects.requireNonNull(parsedValue, "parsedValue");
        }
    }

    record Failure<T>(Finding finding) implements ParseOutcome<T> {
        public Failure {
            Objects.requireNonNull(finding, "finding");
            if (finding.level() != FindingLevel.ERROR) {
                throw new IllegalArgumentException(
                        "parse failure must be recorded as an error-level finding: " + finding.level());
            }
        }
    }

    static <T> ParseOutcome<T> success(T value) {
        return new Success<>(value);
    }

    static <T> ParseOutcome<T> failure(Finding finding) {
        return new Failure<>(finding);
    }

    default boolean isSuccess() {
        return this instanceof Success;
    }

    default Optional<T> value() {
        return this instanceof Success<T> success ? Optional.of(success.parsedValue())
                : Optional.empty();
    }

    default Optional<Finding> failureFinding() {
        return this instanceof Failure<T> failure ? Optional.of(failure.finding())
                : Optional.empty();
    }
}
