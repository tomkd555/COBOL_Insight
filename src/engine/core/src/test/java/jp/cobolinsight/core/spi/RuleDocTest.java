package jp.cobolinsight.core.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleDocTest {

    private static RuleDoc.Builder filled() {
        return RuleDoc.named("桁落ちの検出", "データ移動")
                .summary("受信項目の桁数が送信項目より小さいMOVEを検出する。")
                .rationale("上位桁が失われ、金額が実際より小さく記録される。")
                .detection("数値項目どうしのMOVEで、受信の整数部または小数部が送信より短いもの。")
                .remedy("受信項目のPICTUREを送信項目以上に広げる。");
    }

    @Test
    void buildsWithAllFields() {
        RuleDoc doc = filled().example("MOVE A TO B.", "MOVE A TO C.").build();
        assertEquals("桁落ちの検出", doc.name());
        assertEquals("データ移動", doc.category());
        assertEquals("MOVE A TO B.", doc.badExample());
        assertEquals("MOVE A TO C.", doc.goodExample());
        assertTrue(doc.hasExample());
    }

    @Test
    void exampleIsOptional() {
        RuleDoc doc = filled().build();
        assertEquals("", doc.badExample());
        assertEquals("", doc.goodExample());
        assertFalse(doc.hasExample());
    }

    /** 片方だけの例は対比にならないため、揃っていないものは例なしとして扱う。 */
    @Test
    void oneSidedExampleIsNotAnExample() {
        RuleDoc doc = filled().example("MOVE A TO B.", "").build();
        assertFalse(doc.hasExample());
    }

    @Test
    void trimsSurroundingWhitespace() {
        RuleDoc doc = filled().example("  MOVE A TO B.\n", "\nMOVE A TO C.  ").build();
        assertEquals("MOVE A TO B.", doc.badExample());
        assertEquals("MOVE A TO C.", doc.goodExample());
    }

    @Test
    void rejectsBlankRequiredField() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleDoc.named("名称", "分類").summary(" ").rationale("理由")
                        .detection("条件").remedy("対処").build());
    }

    @Test
    void rejectsMissingRemedy() {
        assertThrows(IllegalArgumentException.class,
                () -> RuleDoc.named("名称", "分類").summary("要約").rationale("理由")
                        .detection("条件").build());
    }
}
