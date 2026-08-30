package jp.cobolinsight.core.sql;

import jp.cobolinsight.core.source.SourceRange;

import java.util.List;
import java.util.Objects;

/**
 * The parse result of an embedded SQL statement. Holds both the original text (originalText) and
 * the mangled text (mangledText), so rules can report using the original data names. range is
 * the position in the original COBOL source. structureSignals holds the syntax-level structural
 * signals that SQL findings (S001, S002, S004) read.
 */
public record SqlStatementModel(SqlStatementKind kind, String originalText, String mangledText,
        List<HostVariableBinding> hostVariables, List<String> referencedTables, SourceRange range,
        SqlStructureSignals structureSignals) {

    public SqlStatementModel {
        Objects.requireNonNull(kind, "kind");
        if (originalText == null || originalText.isBlank()) {
            throw new IllegalArgumentException("originalText must not be blank");
        }
        if (mangledText == null || mangledText.isBlank()) {
            throw new IllegalArgumentException("mangledText must not be blank");
        }
        hostVariables = List.copyOf(hostVariables);
        referencedTables = List.copyOf(referencedTables);
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(structureSignals, "structureSignals");
    }
}
