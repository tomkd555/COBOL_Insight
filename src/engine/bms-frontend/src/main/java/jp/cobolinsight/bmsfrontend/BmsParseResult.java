package jp.cobolinsight.bmsfrontend;

import java.util.List;

/** BMS ソース1本の解析結果。 */
public record BmsParseResult(List<BmsMapset> mapsets, List<BmsParseError> errors) {

    public BmsParseResult {
        mapsets = List.copyOf(mapsets);
        errors = List.copyOf(errors);
    }
}
