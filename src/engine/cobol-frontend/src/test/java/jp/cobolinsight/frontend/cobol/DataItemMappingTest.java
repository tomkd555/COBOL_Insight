package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that SYKCPY1's contents (REDEFINES, OCCURS, COMP-3, level 88) are mapped to DataItem. */
class DataItemMappingTest {

    /** SYK003 expands SYKCPY1 into the LINKAGE SECTION without any substitution. */
    private DataItem sykcpy1Record() {
        CobolSemanticModel model = TestSources.model("SYK003.cbl");
        return find(model.dataItems(), "SYK1-受注レコード");
    }

    private static DataItem find(List<DataItem> items, String name) {
        List<DataItem> flat = new ArrayList<>();
        flatten(items, flat);
        return flat.stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("DataItem が見つからない: " + name));
    }

    private static void flatten(List<DataItem> items, List<DataItem> out) {
        for (DataItem item : items) {
            out.add(item);
            flatten(item.children(), out);
        }
    }

    @Test
    void recordComesFromCopybookLine7() {
        DataItem record = sykcpy1Record();
        assertEquals(1, record.level());
        assertTrue(record.position().file().endsWith("SYKCPY1.cpy"));
        assertEquals(7, record.position().line());
    }

    @Test
    void redefinesIsMappedWithTargetName() {
        DataItem ymd = find(List.of(sykcpy1Record()), "SYK1-受注日-YMD");
        assertEquals(Optional.of("SYK1-受注日"), ymd.redefines());
        assertEquals(10, ymd.position().line());
        assertEquals(3, ymd.children().size());
    }

    @Test
    void occursIsMapped() {
        DataItem line = find(List.of(sykcpy1Record()), "SYK1-明細行");
        assertTrue(line.occurs().isPresent());
        assertEquals(10, line.occurs().orElseThrow().minTimes());
        assertEquals(10, line.occurs().orElseThrow().maxTimes());
        assertEquals(17, line.position().line());
    }

    @Test
    void comp3UsageAndPictureAreMapped() {
        DataItem total = find(List.of(sykcpy1Record()), "SYK1-受注金額合計");
        assertEquals(Optional.of("COMP-3"), total.usage());
        assertEquals(Optional.of("S9(09)V99"), total.picture());
        DataItem qty = find(List.of(sykcpy1Record()), "SYK1-数量");
        assertEquals(Optional.of("COMP-3"), qty.usage());
    }

    @Test
    void level88ConditionNamesAreMapped() {
        DataItem kbn = find(List.of(sykcpy1Record()), "SYK1-処理区分");
        assertEquals(3, kbn.conditionNames().size());
        ConditionName first = kbn.conditionNames().get(0);
        assertEquals("SYK1-新規登録", first.name());
        assertEquals(List.of("'1'"), first.values());
        assertEquals(24, first.position().line());
        assertEquals("SYK1-訂正", kbn.conditionNames().get(1).name());
        assertEquals(25, kbn.conditionNames().get(1).position().line());
        assertEquals("SYK1-取消", kbn.conditionNames().get(2).name());
        assertEquals(26, kbn.conditionNames().get(2).position().line());
    }

    @Test
    void valueClauseIsCapturedWhenPresent() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        DataItem eofFlag = find(model.dataItems(), "WS-EOF-FLAG");
        assertEquals(Optional.of("'N'"), eofFlag.value());
        DataItem errorCount = find(model.dataItems(), "WS-エラー件数");
        assertEquals(Optional.of("ZERO"), errorCount.value());
    }

    @Test
    void valueIsAbsentWhenNoValueClause() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        DataItem checkAmount = find(model.dataItems(), "WS-検証金額");
        assertTrue(checkAmount.value().isEmpty());
    }

    @Test
    void parentChildHierarchyIsPreserved() {
        DataItem record = sykcpy1Record();
        List<String> topLevelChildren = record.children().stream().map(DataItem::name).toList();
        assertTrue(topLevelChildren.containsAll(List.of(
                "SYK1-受注番号", "SYK1-受注日", "SYK1-受注日-YMD", "SYK1-得意先コード",
                "SYK1-受注金額合計", "SYK1-明細件数", "SYK1-明細行", "SYK1-処理区分")),
                () -> "直下の子が欠落: " + topLevelChildren);
        DataItem meisai = find(record.children(), "SYK1-明細行");
        assertEquals(List.of("SYK1-商品コード", "SYK1-数量", "SYK1-単価", "SYK1-金額"),
                meisai.children().stream().map(DataItem::name).toList());
    }
}
