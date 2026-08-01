package jp.cobolinsight.engineapi.sql;

import java.util.List;
import java.util.Objects;

/**
 * DECLARE CURSOR のカーソル情報。SQL指摘 S004 が読む。
 *
 * @param cursorName       カーソル名
 * @param forReadOnly      FOR READ ONLY 句の有無
 * @param forFetchOnly     FOR FETCH ONLY 句の有無
 * @param forUpdate        FOR UPDATE(OF 有無を問わず)句の有無
 * @param forUpdateColumns FOR UPDATE OF の対象列名。OF が無ければ空
 */
public record CursorSignals(String cursorName, boolean forReadOnly, boolean forFetchOnly,
        boolean forUpdate, List<String> forUpdateColumns) {

    public CursorSignals {
        Objects.requireNonNull(cursorName, "cursorName");
        forUpdateColumns = List.copyOf(forUpdateColumns);
    }
}
