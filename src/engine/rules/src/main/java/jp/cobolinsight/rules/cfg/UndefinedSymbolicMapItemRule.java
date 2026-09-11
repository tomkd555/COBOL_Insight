package jp.cobolinsight.rules.cfg;

import jp.cobolinsight.core.bms.BmsField;
import jp.cobolinsight.core.bms.BmsMap;
import jp.cobolinsight.core.bms.BmsMapset;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.Severity;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.rule.Needs;
import jp.cobolinsight.core.rule.Rule;
import jp.cobolinsight.core.rule.RuleMeta;
import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.semantic.Procedure;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.AnalysisContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * R033 Reference to a symbolic map item the BMS map does not define. The symbolic map copybook is
 * generated from the mapset; when a field is added to the copybook and the mapset is not
 * regenerated, the program compiles and the item addresses storage the map never fills. For each
 * {@code 01} named after a map with the I/O suffix, every child whose name minus its trailing
 * L/F/A/I/O is no field of that map is undefined, and every statement that names one is reported.
 */
public final class UndefinedSymbolicMapItemRule implements Rule {

    /** The suffixes the symbolic map generator appends to a field name: length, flag, attribute, input, output. */
    private static final String SUFFIXES = "LFAIO";

    private static final RuleMeta META = RuleMeta
            .named("R033", "BMS マップに定義のないシンボリックマップ項目の参照", "CICS")
            .summary("マップに対応する項目がないシンボリックマップの項目を参照する"
                    + "文を検出します。")
            .rationale("マップが埋めない領域を読み書きするため、画面には現れず、"
                    + "隣の項目の内容を書き換えます。")
            .detection("マップ名に I・O を付けた 01 の下の項目のうち、"
                    + "末尾の L・F・A・I・O を除いた名前が"
                    + "そのマップのどの項目名とも一致しないものを求め、"
                    + "その項目を参照する文を検出します。"
                    + "どのマップとも対応しない 01 の下の項目は対象外です。")
            .remedy("BMS のマップ定義に項目を追加してマップを生成し直すか、"
                    + "シンボリックマップから使わなくなった項目を削ってください。")
            .example("""
                    MOVE SPACES TO MEI11O
                    """, """
                    MOVE SPACES TO MEI10O
                    """)
            .severity(Severity.HIGH)
            .commands(Command.LINT)
            .targets(AssetKind.COBOL)
            .build();

    @Override
    public RuleMeta meta() {
        return META;
    }

    @Override
    public List<Finding> evaluate(AnalysisContext context) {
        Map<String, Set<String>> fieldsByMap = fieldsByMap(context.bmsMapsets());
        if (fieldsByMap.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (CobolSemanticModel model : context.cobolPrograms()) {
            evaluate(model, fieldsByMap, findings);
        }
        return findings;
    }

    private static void evaluate(CobolSemanticModel model, Map<String, Set<String>> fieldsByMap,
            List<Finding> findings) {
        // item name -> the map its 01 belongs to, in declaration order.
        Map<String, String> undefined = new LinkedHashMap<>();
        for (DataItem item : model.dataItems()) {
            String name = CfgSupport.upper(item.name());
            if (item.level() != 1 || name.length() < 2) {
                continue;
            }
            char suffix = name.charAt(name.length() - 1);
            if (suffix != 'I' && suffix != 'O') {
                continue;
            }
            String map = name.substring(0, name.length() - 1);
            Set<String> fields = fieldsByMap.get(map);
            if (fields != null) {
                collectUndefined(item.children(), fields, map, undefined);
            }
        }
        if (undefined.isEmpty()) {
            return;
        }
        Set<String> reported = new LinkedHashSet<>();
        for (Procedure procedure : model.procedures()) {
            CfgSupport.walk(procedure.statements(), statement -> {
                String text = CfgSupport.referenceText(statement);
                int line = statement.range().start().line();
                undefined.forEach((item, map) -> {
                    if (!CfgSupport.wordPattern(item).matcher(text).find()
                            || !reported.add(line + " " + item)) {
                        return;
                    }
                    findings.add(Finding.of(META.id(), META.defaultSeverity().toLevel(),
                            item + " は " + map + " に定義のない項目です。"
                                    + "マップとシンボリックマップが食い違っています。",
                            new SourcePosition(model.sourceFile(), line, 1,
                                    SourcePosition.UNKNOWN_BYTE_OFFSET)));
                });
            });
        }
    }

    /** Every non-FILLER descendant whose base name is no field of the map, keyed to the map name. */
    private static void collectUndefined(List<DataItem> items, Set<String> fields, String map,
            Map<String, String> undefined) {
        for (DataItem item : items) {
            String name = CfgSupport.upper(item.name());
            if (!name.equals("FILLER") && !fields.contains(baseNameOf(name))) {
                undefined.putIfAbsent(name, map);
            }
            collectUndefined(item.children(), fields, map, undefined);
        }
    }

    /** The field name a symbolic map item belongs to: the item name with its generated suffix removed. */
    private static String baseNameOf(String itemName) {
        return itemName.length() > 1
                && SUFFIXES.indexOf(itemName.charAt(itemName.length() - 1)) >= 0
                ? itemName.substring(0, itemName.length() - 1) : itemName;
    }

    /** Map name (uppercased) to the names of the fields it defines. Unnamed constant fields are left out. */
    private static Map<String, Set<String>> fieldsByMap(List<BmsMapset> mapsets) {
        Map<String, Set<String>> byMap = new TreeMap<>();
        for (BmsMapset mapset : mapsets) {
            for (BmsMap map : mapset.maps()) {
                Set<String> names = byMap.computeIfAbsent(CfgSupport.upper(map.name()),
                        key -> new LinkedHashSet<>());
                for (BmsField field : map.fields()) {
                    if (!field.name().isBlank()) {
                        names.add(CfgSupport.upper(field.name()));
                    }
                }
            }
        }
        return byMap;
    }
}
