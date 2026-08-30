package jp.cobolinsight.frontend.bms;

import java.util.List;

/** The parse result for a single BMS source. */
public record BmsParseResult(List<BmsMapset> mapsets, List<BmsParseError> errors) {

    public BmsParseResult {
        mapsets = List.copyOf(mapsets);
        errors = List.copyOf(errors);
    }
}
