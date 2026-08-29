package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 段落・節の定義と、含まれる文の列。sectionName は段落が属する節の名前で、節自身と
 * どの節にも属さない段落では empty とする。
 */
public record Procedure(String name, ProcedureKind kind, Optional<String> sectionName,
        List<Statement> statements, SourceRange range) {

    public Procedure {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sectionName, "sectionName");
        statements = List.copyOf(statements);
        Objects.requireNonNull(range, "range");
    }
}
