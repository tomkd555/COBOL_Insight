package jp.cobolinsight.cobolfrontend;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.semantic.DataItem;
import jp.cobolinsight.engineapi.source.CopyExpansionEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 期待結果.md 6章(コピー句の使用状況)どおりの COPY REPLACING 展開の検証。 */
class CopyExpansionMappingTest {

    private static List<String> allItemNames(CobolSemanticModel model) {
        List<String> names = new ArrayList<>();
        collect(model.dataItems(), names);
        return names;
    }

    private static void collect(List<DataItem> items, List<String> out) {
        for (DataItem item : items) {
            out.add(item.name());
            collect(item.children(), out);
        }
    }

    @Test
    void syk001ReplacesSyk1PrefixWithOrd1() {
        List<String> names = allItemNames(TestSources.model("SYK001.cbl"));
        assertTrue(names.contains("ORD1-受注レコード"), () -> "ORD1- 置換後の項目が無い: " + names);
        assertTrue(names.contains("ORD1-金額"));
        assertTrue(names.contains("ORD1-明細行"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("SYK1-")),
                "置換前の SYK1- 項目が残っている");
    }

    @Test
    void syk002ReplacesSyk1PrefixWithIn1() {
        List<String> names = allItemNames(TestSources.model("SYK002.cbl"));
        assertTrue(names.contains("IN1-受注レコード"));
        assertTrue(names.contains("IN1-商品コード"));
        assertFalse(names.stream().anyMatch(n -> n.startsWith("SYK1-")));
        assertTrue(names.contains("SYK2-受注番号"), "SYKCPY2 は置換なしで展開されること");
    }

    @Test
    void syk003ExpandsWithoutReplacing() {
        List<String> names = allItemNames(TestSources.model("SYK003.cbl"));
        assertTrue(names.contains("SYK1-受注レコード"));
        assertTrue(names.contains("SYK1-金額"));
    }

    @Test
    void syk001CopyExpansionEntryPointsToSykcpy1() {
        CobolSemanticModel model = TestSources.model("SYK001.cbl");
        CopyExpansionEntry entry = model.copyExpansions().stream()
                .filter(e -> e.copybookPath().endsWith("SYKCPY1.cpy"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "SYKCPY1.cpy の展開対応が無い: " + model.copyExpansions()));
        assertTrue(entry.expandedEndLine() >= entry.expandedStartLine());
        assertTrue(entry.copybookStartLine() >= 7,
                () -> "コピーブック側の開始行が 01 レベル(7行目)以降を指すこと: " + entry);
    }

    @Test
    void implicitCopybooksAreExcluded() {
        CobolSemanticModel model = TestSources.model("SYK006.cbl");
        assertFalse(allItemNames(model).contains("SQLCA"),
                "暗黙コード(SQLCA)のデータ項目は除外されること");
        assertTrue(model.copyExpansions().stream()
                .noneMatch(e -> e.copybookPath().contains("implicit-code")));
        assertTrue(model.copyExpansions().stream()
                .anyMatch(e -> e.copybookPath().endsWith("SYKCPY3.cpy")));
    }

    @Test
    void syk002HasEntriesForBothCopybooks() {
        CobolSemanticModel model = TestSources.model("SYK002.cbl");
        assertTrue(model.copyExpansions().stream()
                .anyMatch(e -> e.copybookPath().endsWith("SYKCPY1.cpy")));
        assertTrue(model.copyExpansions().stream()
                .anyMatch(e -> e.copybookPath().endsWith("SYKCPY2.cpy")));
    }
}
