package jp.cobolinsight.core.semantic;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A paragraph or section definition and its contained statement sequence. sectionName is the
 * name of the section a paragraph belongs to; it is empty for a section itself and for a
 * paragraph that belongs to no section.
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
