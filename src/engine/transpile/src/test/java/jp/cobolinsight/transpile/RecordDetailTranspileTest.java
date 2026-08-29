package jp.cobolinsight.transpile;

import jp.cobolinsight.core.semantic.ConditionName;
import jp.cobolinsight.core.semantic.DataItem;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.transpile.emit.JavaEmitter;
import jp.cobolinsight.transpile.emit.LineTrackingEmitter;
import jp.cobolinsight.transpile.emit.RecordClassGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Record-translation details. Verifies REDEFINES name matching and how figurative constants in level-88 VALUE clauses are mapped over. */
class RecordDetailTranspileTest {

    private static final SourcePosition POSITION = SourcePosition.fileStart("t.cbl");

    @Test
    void redefinesTargetIsMatchedIgnoringCase() {
        DataItem record = group("WS-REC", List.of(
                leaf(5, "WS-DATE", "X(08)", Optional.empty(), List.of()),
                new DataItem(5, "WS-DATE-YMD", Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of("ws-date"), Optional.empty(), List.of(),
                        List.of(leaf(10, "WS-YEAR", "X(04)", Optional.empty(), List.of()),
                                leaf(10, "WS-MMDD", "X(04)", Optional.empty(), List.of())),
                        POSITION),
                leaf(5, "WS-NEXT", "X(02)", Optional.empty(), List.of())));

        LayoutField layout = RecordLayoutResolver.resolve(record);

        assertEquals(0, layout.find("WS-DATE").orElseThrow().offset());
        assertEquals(0, layout.find("WS-DATE-YMD").orElseThrow().offset(),
                "REDEFINES の対象名は大小を区別せず突き合わせること");
        assertEquals(8, layout.find("WS-NEXT").orElseThrow().offset());
        assertEquals(10, layout.byteLength());
    }

    @Test
    void figurativeConstantInConditionValueIsTranslated() {
        DataItem record = group("WS-REC", List.of(
                leaf(5, "WS-NAME", "X(10)", Optional.empty(),
                        List.of(new ConditionName("WS-NAME-EMPTY", List.of("SPACES"), POSITION))),
                leaf(5, "WS-CNT", "9(03)", Optional.empty(),
                        List.of(new ConditionName("WS-CNT-NONE", List.of("ZERO"), POSITION),
                                new ConditionName("WS-CNT-HIGH", List.of("HIGH-VALUES"), POSITION)))));

        String generated = generateJava(record);

        assertTrue(generated.contains("return get_WS_NAME().equals(\" \");"),
                () -> "SPACES を空白文字へ写すこと: " + generated);
        assertTrue(generated.contains("return get_WS_CNT() == 0;"),
                () -> "ZERO を数値 0 へ写すこと: " + generated);
        assertTrue(generated.contains("return false /* HIGH-VALUES"),
                () -> "文字コード系に依存する表意定数は注記付きで対訳しないこと: " + generated);
    }

    private static String generateJava(DataItem record) {
        LineTrackingEmitter out = new LineTrackingEmitter("    ");
        RecordClassGenerator.generate("T", "WS_REC.java", record,
                RecordLayoutResolver.resolve(record), new JavaEmitter(), out);
        return out.render();
    }

    private static DataItem group(String name, List<DataItem> children) {
        return new DataItem(1, name, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of(), children, POSITION);
    }

    private static DataItem leaf(int level, String name, String picture, Optional<String> usage,
            List<ConditionName> conditionNames) {
        return new DataItem(level, name, Optional.of(picture), usage, Optional.empty(),
                Optional.empty(), Optional.empty(), conditionNames, List.of(), POSITION);
    }
}
