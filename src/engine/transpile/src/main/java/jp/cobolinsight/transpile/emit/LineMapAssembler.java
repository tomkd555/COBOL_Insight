package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.LineMappingEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 未採番の行対応({@link PendingMapping})を安定キー(COBOL開始行→COBOL元ソース→生成ファイル→生成開始行→…)で
 * 整列し、その順で anchorId を決定論的に採番して {@link LineMappingEntry} 群を完成させる。乱数・時刻・ハッシュに依存しない。
 * anchorId の接頭辞にはプログラム識別子を単一で用い、各エントリの cobolSourceId(宣言元ソース)とは独立させる。
 */
public final class LineMapAssembler {

    private static final Comparator<PendingMapping> STABLE_ORDER =
            Comparator.comparingInt((PendingMapping m) -> m.cobolLines().startLine())
                    .thenComparing(PendingMapping::cobolSourceId)
                    .thenComparing(PendingMapping::generatedFile)
                    .thenComparingInt(m -> m.generatedLines().startLine())
                    .thenComparingInt(m -> m.generatedLines().endLine())
                    .thenComparingInt(m -> m.cobolLines().endLine())
                    .thenComparing(PendingMapping::note);

    private LineMapAssembler() {
    }

    /** anchorId は {@code <anchorPrefix>#NNNN}(1始まり・4桁ゼロ埋め・整列順)。 */
    public static List<LineMappingEntry> assemble(String anchorPrefix, List<PendingMapping> pending) {
        List<PendingMapping> sorted = new ArrayList<>(pending);
        sorted.sort(STABLE_ORDER);
        List<LineMappingEntry> entries = new ArrayList<>(sorted.size());
        int seq = 0;
        for (PendingMapping m : sorted) {
            seq++;
            String anchorId = anchorPrefix + "#" + String.format("%04d", seq);
            entries.add(new LineMappingEntry(m.cobolSourceId(), m.cobolLines(), m.generatedFile(),
                    m.generatedLines(), m.mappingKind(), m.note(), anchorId));
        }
        return List.copyOf(entries);
    }
}
