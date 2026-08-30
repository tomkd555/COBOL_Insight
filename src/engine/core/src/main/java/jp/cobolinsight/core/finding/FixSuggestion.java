package jp.cobolinsight.core.finding;

import java.util.List;

/** A fix suggestion corresponding to a SARIF fix. Composed of one or more minimal edits. */
public record FixSuggestion(String description, List<TextEdit> edits) {

    public FixSuggestion {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        edits = List.copyOf(edits);
        if (edits.isEmpty()) {
            throw new IllegalArgumentException("edits must not be empty");
        }
    }
}
