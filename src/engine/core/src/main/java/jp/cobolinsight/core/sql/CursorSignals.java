package jp.cobolinsight.core.sql;

import java.util.List;
import java.util.Objects;

/**
 * Cursor information for DECLARE CURSOR. Read by SQL finding S004.
 *
 * @param cursorName       the cursor name
 * @param forReadOnly      whether a FOR READ ONLY clause is present
 * @param forFetchOnly     whether a FOR FETCH ONLY clause is present
 * @param forUpdate        whether a FOR UPDATE clause is present (regardless of OF)
 * @param forUpdateColumns the target column names of FOR UPDATE OF; empty if there is no OF
 */
public record CursorSignals(String cursorName, boolean forReadOnly, boolean forFetchOnly,
        boolean forUpdate, List<String> forUpdateColumns) {

    public CursorSignals {
        Objects.requireNonNull(cursorName, "cursorName");
        forUpdateColumns = List.copyOf(forUpdateColumns);
    }
}
