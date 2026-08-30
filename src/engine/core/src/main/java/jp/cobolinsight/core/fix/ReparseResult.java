package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.finding.Finding;

import java.util.Objects;
import java.util.Optional;

/**
 * The result of reparse verification. On success, {@code success=true} and no finding is held.
 * On failure, {@code success=false}, and the cause is held as the {@code ParseOutcome.Failure}
 * error-level finding.
 */
public record ReparseResult(boolean success, Optional<Finding> errorFinding) {

    public ReparseResult {
        Objects.requireNonNull(errorFinding, "errorFinding");
        if (success == errorFinding.isPresent()) {
            throw new IllegalArgumentException(
                    "成功なら finding を持たず、失敗なら finding を持つ: success=" + success
                            + ", finding=" + errorFinding.orElse(null));
        }
    }
}
