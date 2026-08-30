package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;

/** A compound statement for a branch or loop. conditionText is the textual form of the condition expression. */
public record CompoundStatement(ControlKind kind, String conditionText, List<StatementBlock> blocks,
        SourceRange range) implements Statement {

    public CompoundStatement {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(conditionText, "conditionText");
        blocks = List.copyOf(blocks);
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("blocks must not be empty");
        }
        Objects.requireNonNull(range, "range");
    }
}
