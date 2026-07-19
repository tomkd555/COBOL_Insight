package jp.cobolinsight.bmsfrontend;

import jp.cobolinsight.bmsfrontend.grammar.BmsMapLexer;
import jp.cobolinsight.bmsfrontend.grammar.BmsMapParser;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 構文木からマップセット→マップ→フィールドの階層モデルを組み立てる。 */
final class BmsModelBuilder {

    private final List<BmsParseError> errors;
    private final List<BmsMapset> mapsets = new ArrayList<>();

    private MapsetAcc currentMapset;
    private MapAcc currentMap;

    BmsModelBuilder(List<BmsParseError> errors) {
        this.errors = errors;
    }

    BmsParseResult build(BmsMapParser.MapFileContext tree) {
        for (BmsMapParser.StatementContext stmt : tree.statement()) {
            BmsMapParser.MacroContext macro = stmt.macro();
            if (macro == null) {
                continue; // END
            }
            String label = stmt.label != null ? stmt.label.getText() : null;
            int line = (stmt.label != null ? stmt.label : macro.kind).getLine();
            Map<String, BmsMapParser.ValueContext> params = paramsOf(macro);
            switch (macro.kind.getType()) {
                case BmsMapLexer.DFHMSD -> onMapset(label, line, params);
                case BmsMapLexer.DFHMDI -> onMap(label, line, params);
                case BmsMapLexer.DFHMDF -> onField(label, line, params);
                default -> throw new IllegalStateException(macro.kind.getText());
            }
        }
        closeMapset();
        return new BmsParseResult(mapsets, errors);
    }

    private void onMapset(String label, int line, Map<String, BmsMapParser.ValueContext> params) {
        List<String> type = flatten(params.get("TYPE"));
        boolean isFinal = type.stream().anyMatch(t -> t.equalsIgnoreCase("FINAL"));
        closeMapset();
        if (!isFinal) {
            currentMapset = new MapsetAcc(label, line);
        }
    }

    private void onMap(String label, int line, Map<String, BmsMapParser.ValueContext> params) {
        closeMap();
        if (currentMapset == null) {
            errors.add(new BmsParseError(line, 0, "DFHMSD の外で DFHMDI が現れた"));
            return;
        }
        MapAcc map = new MapAcc(label, line);
        List<Integer> size = intPair(params.get("SIZE"), line, "SIZE");
        map.sizeRows = size.get(0);
        map.sizeColumns = size.get(1);
        map.positionLine = intValue(params.get("LINE"), line, "LINE");
        map.positionColumn = intValue(params.get("COLUMN"), line, "COLUMN");
        currentMap = map;
    }

    private void onField(String label, int line, Map<String, BmsMapParser.ValueContext> params) {
        if (currentMap == null) {
            errors.add(new BmsParseError(line, 0, "DFHMDI の外で DFHMDF が現れた"));
            return;
        }
        List<Integer> pos = intPair(params.get("POS"), line, "POS");
        Integer length = intValue(params.get("LENGTH"), line, "LENGTH");
        List<String> attributes = flatten(params.get("ATTRB"));
        currentMap.fields.add(new BmsField(label, line, pos.get(0), pos.get(1), length, attributes));
    }

    private void closeMap() {
        if (currentMap != null) {
            currentMapset.maps.add(new BmsMap(currentMap.name, currentMap.sourceLine,
                    currentMap.sizeRows, currentMap.sizeColumns,
                    currentMap.positionLine, currentMap.positionColumn, currentMap.fields));
            currentMap = null;
        }
    }

    private void closeMapset() {
        closeMap();
        if (currentMapset != null) {
            mapsets.add(new BmsMapset(currentMapset.name, currentMapset.sourceLine, currentMapset.maps));
            currentMapset = null;
        }
    }

    private static Map<String, BmsMapParser.ValueContext> paramsOf(BmsMapParser.MacroContext macro) {
        Map<String, BmsMapParser.ValueContext> params = new LinkedHashMap<>();
        if (macro.paramList() != null) {
            for (BmsMapParser.ParamContext param : macro.paramList().param()) {
                params.put(param.key.getText().toUpperCase(), param.value());
            }
        }
        return params;
    }

    /** 値を平坦な文字列並びにする。括弧はネストごとに展開し、文字列は引用符を外す。 */
    private static List<String> flatten(BmsMapParser.ValueContext value) {
        List<String> out = new ArrayList<>();
        collect(value, out);
        return out;
    }

    private static void collect(BmsMapParser.ValueContext value, List<String> out) {
        switch (value) {
            case null -> { }
            case BmsMapParser.GroupValueContext group -> {
                for (BmsMapParser.ValueContext inner : group.value()) {
                    collect(inner, out);
                }
            }
            case BmsMapParser.StringValueContext str -> {
                String text = str.getText();
                out.add(text.substring(1, text.length() - 1).replace("''", "'"));
            }
            default -> out.add(value.getText());
        }
    }

    private Integer intValue(BmsMapParser.ValueContext value, int line, String key) {
        if (value == null) {
            return null;
        }
        List<String> parts = flatten(value);
        Integer parsed = parts.size() == 1 ? tryParseInt(parts.get(0)) : null;
        if (parsed == null) {
            errors.add(new BmsParseError(line, startColumn(value), key + " の値が数値でない"));
        }
        return parsed;
    }

    /** 常に2要素を返す。未指定・不正のとき要素は null。 */
    private List<Integer> intPair(BmsMapParser.ValueContext value, int line, String key) {
        if (value == null) {
            return Arrays.asList(null, null);
        }
        List<String> parts = flatten(value);
        if (parts.size() == 2) {
            Integer first = tryParseInt(parts.get(0));
            Integer second = tryParseInt(parts.get(1));
            if (first != null && second != null) {
                return List.of(first, second);
            }
        }
        errors.add(new BmsParseError(line, startColumn(value), key + " の値が (数値,数値) でない"));
        return Arrays.asList(null, null);
    }

    private static Integer tryParseInt(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int startColumn(BmsMapParser.ValueContext value) {
        Token start = value.getStart();
        return start != null ? start.getCharPositionInLine() : 0;
    }

    private static final class MapsetAcc {
        final String name;
        final int sourceLine;
        final List<BmsMap> maps = new ArrayList<>();

        MapsetAcc(String name, int sourceLine) {
            this.name = name;
            this.sourceLine = sourceLine;
        }
    }

    private static final class MapAcc {
        final String name;
        final int sourceLine;
        final List<BmsField> fields = new ArrayList<>();
        Integer sizeRows;
        Integer sizeColumns;
        Integer positionLine;
        Integer positionColumn;

        MapAcc(String name, int sourceLine) {
            this.name = name;
            this.sourceLine = sourceLine;
        }
    }
}
