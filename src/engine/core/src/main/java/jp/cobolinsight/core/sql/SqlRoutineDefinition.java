package jp.cobolinsight.core.sql;

import java.util.List;

/**
 * A routine an SQL script defines — a native SQL PL procedure, function or trigger — and the
 * statements its body runs.
 *
 * <p>{@code name} is written as the CREATE statement writes it, schema qualifier included;
 * {@code definedIn} is the script's path relative to the asset folder, and {@code line} the line
 * its CREATE starts on. A body statement carries the line it stands on in the script, so what is
 * reported about it sends a reader to the statement rather than to the head of the routine.
 */
public record SqlRoutineDefinition(String name, String definedIn, int line,
        List<SqlStatementModel> body) {

    public SqlRoutineDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (definedIn == null || definedIn.isBlank()) {
            throw new IllegalArgumentException("definedIn must not be blank");
        }
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1: " + line);
        }
        body = List.copyOf(body);
    }

    /** The name without its schema qualifier, which is how a CALL and the call graph match it. */
    public String unqualifiedName() {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
