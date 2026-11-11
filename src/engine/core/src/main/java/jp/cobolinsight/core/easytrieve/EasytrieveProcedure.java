package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/** A {@code name. PROC … END-PROC} block and the statements in it, nested ones included, in order. */
public record EasytrieveProcedure(String name, List<EasytrieveStatement> statements,
        SourcePosition position) {

    public EasytrieveProcedure {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        statements = List.copyOf(statements);
        Objects.requireNonNull(position, "position");
    }
}
