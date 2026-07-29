package jp.cobolinsight.bmsfrontend;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析結果を engine-api のBMSマップモデルへ変換する公開アダプタ。engine-api 側は必須値のため、
 * 未指定の SIZE は端末画面の既定 24x80、未指定の POS は (1,1)、未指定の LENGTH は 0、
 * ラベル無しフィールド名は空文字列で補う。
 */
public final class BmsModelMapper {

    private static final int DEFAULT_ROWS = 24;
    private static final int DEFAULT_COLS = 80;

    private BmsModelMapper() {
    }

    public static List<jp.cobolinsight.engineapi.bms.BmsMapset> toEngineApi(BmsParseResult result,
            String sourceFile) {
        List<jp.cobolinsight.engineapi.bms.BmsMapset> mapsets = new ArrayList<>();
        for (BmsMapset mapset : result.mapsets()) {
            List<jp.cobolinsight.engineapi.bms.BmsMap> maps = new ArrayList<>();
            for (BmsMap map : mapset.maps()) {
                maps.add(new jp.cobolinsight.engineapi.bms.BmsMap(
                        nameOrFallback(map.name(), map.sourceLine()),
                        map.sizeRows() == null ? DEFAULT_ROWS : map.sizeRows(),
                        map.sizeColumns() == null ? DEFAULT_COLS : map.sizeColumns(),
                        toEngineApiFields(map.fields())));
            }
            mapsets.add(new jp.cobolinsight.engineapi.bms.BmsMapset(
                    nameOrFallback(mapset.name(), mapset.sourceLine()), sourceFile, maps));
        }
        return List.copyOf(mapsets);
    }

    private static List<jp.cobolinsight.engineapi.bms.BmsField> toEngineApiFields(
            List<BmsField> fields) {
        List<jp.cobolinsight.engineapi.bms.BmsField> mapped = new ArrayList<>();
        for (BmsField field : fields) {
            mapped.add(new jp.cobolinsight.engineapi.bms.BmsField(
                    field.name() == null ? "" : field.name(),
                    field.posRow() == null ? 1 : field.posRow(),
                    field.posColumn() == null ? 1 : field.posColumn(),
                    field.length() == null ? 0 : field.length(),
                    String.join(",", field.attributes())));
        }
        return mapped;
    }

    /** engine-api はマップセット名・マップ名を必須とするため、ラベル無しには行番号を添えた名前を与える。 */
    private static String nameOrFallback(String name, int sourceLine) {
        return name == null || name.isBlank() ? "UNNAMED-" + sourceLine : name;
    }
}
