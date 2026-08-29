package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.LineMappingEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts unnumbered line correspondences ({@link PendingMapping}) by a stable key (COBOL start
 * line -> COBOL source -> generated file -> generated start line -> ...), assigns anchorId
 * deterministically in that order, and completes the {@link LineMappingEntry} list. Does not depend
 * on randomness, time, or hashing. A single program identifier is used as the anchorId prefix,
 * independent of each entry's cobolSourceId (the declaring source).
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

    /** anchorId is {@code <anchorPrefix>#NNNN} (1-based, zero-padded to 4 digits, in sort order). */
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
