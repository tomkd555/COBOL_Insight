package jp.cobolinsight.frontend.bms;

import java.util.ArrayList;
import java.util.List;

/**
 * A public adapter that converts a parse result into engine-api's BMS map model. Since engine-api
 * requires values, an unspecified SIZE is filled with the terminal screen default of 24x80, an
 * unspecified POS with (1,1), an unspecified LENGTH with 0, and an unlabeled field name with an
 * empty string.
 */
public final class BmsModelMapper {

    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLS = 80;

    private BmsModelMapper() {
    }

    public static List<jp.cobolinsight.core.bms.BmsMapset> toEngineApi(BmsParseResult result,
            String sourceFile) {
        List<jp.cobolinsight.core.bms.BmsMapset> mapsets = new ArrayList<>();
        for (BmsMapset mapset : result.mapsets()) {
            List<jp.cobolinsight.core.bms.BmsMap> maps = new ArrayList<>();
            for (BmsMap map : mapset.maps()) {
                maps.add(new jp.cobolinsight.core.bms.BmsMap(
                        nameOrFallback(map.name(), map.sourceLine()),
                        map.sizeRows() == null ? DEFAULT_ROWS : map.sizeRows(),
                        map.sizeColumns() == null ? DEFAULT_COLS : map.sizeColumns(),
                        toEngineApiFields(map.fields())));
            }
            mapsets.add(new jp.cobolinsight.core.bms.BmsMapset(
                    nameOrFallback(mapset.name(), mapset.sourceLine()), sourceFile, maps));
        }
        return List.copyOf(mapsets);
    }

    private static List<jp.cobolinsight.core.bms.BmsField> toEngineApiFields(
            List<BmsField> fields) {
        List<jp.cobolinsight.core.bms.BmsField> mapped = new ArrayList<>();
        for (BmsField field : fields) {
            mapped.add(new jp.cobolinsight.core.bms.BmsField(
                    field.name() == null ? "" : field.name(),
                    field.posRow() == null ? 1 : field.posRow(),
                    field.posColumn() == null ? 1 : field.posColumn(),
                    field.length() == null ? 0 : field.length(),
                    String.join(",", field.attributes())));
        }
        return mapped;
    }

    /** engine-api requires mapset and map names, so an unlabeled one is given a name with its line number appended. */
    private static String nameOrFallback(String name, int sourceLine) {
        return name == null || name.isBlank() ? "UNNAMED-" + sourceLine : name;
    }
}
