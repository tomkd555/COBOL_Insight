package jp.cobolinsight.transpile.emit;

import jp.cobolinsight.engineapi.linemap.MappingKind;
import jp.cobolinsight.engineapi.source.LineRange;

/**
 * anchorId 採番前の行対応。{@link LineMapAssembler} が安定キー順に整列してから anchorId を割り当て、
 * {@link jp.cobolinsight.engineapi.linemap.LineMappingEntry} を完成させる。
 */
public record PendingMapping(String cobolSourceId, LineRange cobolLines, String generatedFile,
        LineRange generatedLines, MappingKind mappingKind, String note) {
}
