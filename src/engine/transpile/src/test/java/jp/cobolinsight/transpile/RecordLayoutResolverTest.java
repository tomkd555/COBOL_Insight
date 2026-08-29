package jp.cobolinsight.transpile;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.semantic.DataItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the expected byte layout of SYKCPY1.cpy. SYK003 expands SYKCPY1 into the LINKAGE SECTION
 * unmodified. The ground truth is samples/expected-results.md and the record-layout resolution rules
 * (order number@0 / order date@10 / YMD REDEFINES@10 / customer@18 / total amount@24 len6 / detail
 * count@30 len2 / detail line@32 element 22 x10 / process kind@252, total length 253).
 */
class RecordLayoutResolverTest {

    private LayoutField layout() {
        CobolSemanticModel model = SampleModels.model("SYK003.cbl");
        DataItem record = SampleModels.findItem(model, "SYK1-受注レコード");
        return RecordLayoutResolver.resolve(record);
    }

    private LayoutField field(String name) {
        return layout().find(name)
                .orElseThrow(() -> new AssertionError("LayoutField が見つからない: " + name));
    }

    @Test
    void recordTotalLengthIs253() {
        LayoutField record = layout();
        assertEquals(0, record.offset());
        assertEquals(253, record.byteLength());
    }

    @Test
    void orderNumberIsAtOffsetZeroLength10() {
        LayoutField f = field("SYK1-受注番号");
        assertEquals(0, f.offset());
        assertEquals(10, f.byteLength());
    }

    @Test
    void orderDateIsAtOffset10Length8() {
        LayoutField f = field("SYK1-受注日");
        assertEquals(10, f.offset());
        assertEquals(8, f.byteLength());
    }

    @Test
    void redefinesSharesStartOffsetWithOriginal() {
        LayoutField ymd = field("SYK1-受注日-YMD");
        assertEquals(10, ymd.offset());
        assertEquals(8, ymd.byteLength());
        assertTrue(ymd.redefines().isPresent());
        assertEquals("SYK1-受注日", ymd.redefines().orElseThrow());
        assertEquals(10, field("SYK1-受注日-年").offset());
        assertEquals(4, field("SYK1-受注日-年").byteLength());
        assertEquals(14, field("SYK1-受注日-月").offset());
        assertEquals(2, field("SYK1-受注日-月").byteLength());
        assertEquals(16, field("SYK1-受注日-日").offset());
        assertEquals(2, field("SYK1-受注日-日").byteLength());
    }

    @Test
    void customerCodeAfterRedefinesIsAtOffset18() {
        LayoutField f = field("SYK1-得意先コード");
        assertEquals(18, f.offset());
        assertEquals(6, f.byteLength());
    }

    @Test
    void packedTotalAmountIsAtOffset24Length6() {
        LayoutField f = field("SYK1-受注金額合計");
        assertEquals(24, f.offset());
        assertEquals(6, f.byteLength());
    }

    @Test
    void packedDetailCountIsAtOffset30Length2() {
        LayoutField f = field("SYK1-明細件数");
        assertEquals(30, f.offset());
        assertEquals(2, f.byteLength());
    }

    @Test
    void occursRowIsAtOffset32Element22Times10() {
        LayoutField f = field("SYK1-明細行");
        assertEquals(32, f.offset());
        assertEquals(22, f.byteLength());
        assertEquals(10, f.occurs());
        assertEquals(220, f.totalSpan());
    }

    @Test
    void occursElementChildrenHaveExpectedLengthsAndRelativeOffsets() {
        LayoutField row = field("SYK1-明細行");
        LayoutField goods = field("SYK1-商品コード");
        LayoutField qty = field("SYK1-数量");
        LayoutField price = field("SYK1-単価");
        LayoutField amount = field("SYK1-金額");
        assertEquals(8, goods.byteLength());
        assertEquals(3, qty.byteLength());
        assertEquals(5, price.byteLength());
        assertEquals(6, amount.byteLength());
        assertEquals(0, goods.offset() - row.offset());
        assertEquals(8, qty.offset() - row.offset());
        assertEquals(11, price.offset() - row.offset());
        assertEquals(16, amount.offset() - row.offset());
    }

    @Test
    void processKindIsAtOffset252Length1WithThreeConditionNames() {
        LayoutField f = field("SYK1-処理区分");
        assertEquals(252, f.offset());
        assertEquals(1, f.byteLength());
        assertEquals(3, f.conditionNames().size());
    }
}
