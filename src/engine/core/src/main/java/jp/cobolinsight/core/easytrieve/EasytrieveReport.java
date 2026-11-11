package jp.cobolinsight.core.easytrieve;

import jp.cobolinsight.core.source.SourcePosition;

import java.util.List;
import java.util.Objects;

/**
 * A REPORT subactivity: its definition statements (SEQUENCE, CONTROL, TITLE, LINE and the rest)
 * and the report procedures written under it.
 */
public record EasytrieveReport(String name, EasytrieveStatement header,
        List<EasytrieveStatement> statements, List<EasytrieveProcedure> procedures,
        SourcePosition position) {

    public EasytrieveReport {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(header, "header");
        statements = List.copyOf(statements);
        procedures = List.copyOf(procedures);
        Objects.requireNonNull(position, "position");
    }
}
