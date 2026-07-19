package jp.cobolinsight.engineapi.semantic;

import jp.cobolinsight.engineapi.source.SourceRange;

import java.util.List;
import java.util.Objects;

/** 段落・節の定義と、含まれる文の列。 */
public record Procedure(String name, ProcedureKind kind, List<Statement> statements,
        SourceRange range) {

    public Procedure {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        statements = List.copyOf(statements);
        Objects.requireNonNull(range, "range");
    }
}
