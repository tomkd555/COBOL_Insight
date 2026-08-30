package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.core.linemap.MappingKind;
import jp.cobolinsight.core.source.LineRange;

/**
 * A line correspondence before anchorId assignment. {@link LineMapAssembler} sorts these by a
 * stable key, then assigns anchorId to complete a {@link jp.cobolinsight.core.linemap.LineMappingEntry}.
 */
public record PendingMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
        LineRange generatedLines, MappingKind mappingKind, String note) {
}
