package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * GO TO文。DEPENDING ON形式は複数の飛び先と変数名を持つ。ALTER対象の飛び先未指定形式を
 * 許すため、targets は空を許容する。
 */
public record GoToStatement(List<String> targets, Optional<String> dependingOn, SourceRange range)
        implements Statement {

    public GoToStatement {
        targets = List.copyOf(targets);
        Objects.requireNonNull(dependingOn, "dependingOn");
        Objects.requireNonNull(range, "range");
    }
}
