package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.finding.Finding;

import java.util.Objects;
import java.util.Optional;

/**
 * 再パース検証の結果。成功なら {@code success=true} で finding を持たない。失敗なら
 * {@code success=false} で、原因を {@code ParseOutcome.Failure} の error レベル finding として保持する。
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
