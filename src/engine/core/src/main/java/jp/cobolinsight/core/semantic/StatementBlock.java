package jp.cobolinsight.core.semantic;

import java.util.List;
import java.util.Objects;

/** One branch/body of a compound statement. label is the THEN/ELSE/WHEN clause content, an empty string (loop body), etc. */
public record StatementBlock(String label, List<Statement> statements) {

    public StatementBlock {
        Objects.requireNonNull(label, "label");
        statements = List.copyOf(statements);
    }
}
