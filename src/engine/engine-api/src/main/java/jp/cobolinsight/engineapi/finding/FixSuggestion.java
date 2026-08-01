package jp.cobolinsight.engineapi.finding;

import java.util.List;

/** SARIF の fix に対応する修正案。1件以上の最小編集で構成する。 */
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
