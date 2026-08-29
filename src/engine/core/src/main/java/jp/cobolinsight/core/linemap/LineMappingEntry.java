package jp.cobolinsight.core.linemap;

import jp.cobolinsight.core.source.LineRange;

import java.util.Objects;

/**
 * 逐語対訳の行対応表の1行。COBOL行と生成行(Python/Java)を双方向に対応づける。
 * note は直訳不能構文の注記で、直訳可能な場合は空文字列。anchorId はGUIリンク用アンカー。
 */
public record LineMappingEntry(String cobolSourceId, LineRange cobolLines, String generatedFile,
        LineRange generatedLines, MappingKind mappingKind, String note, String anchorId) {

    public LineMappingEntry {
        if (cobolSourceId == null || cobolSourceId.isBlank()) {
            throw new IllegalArgumentException("cobolSourceId must not be blank");
        }
        Objects.requireNonNull(cobolLines, "cobolLines");
        if (generatedFile == null || generatedFile.isBlank()) {
            throw new IllegalArgumentException("generatedFile must not be blank");
        }
        Objects.requireNonNull(generatedLines, "generatedLines");
        Objects.requireNonNull(mappingKind, "mappingKind");
        Objects.requireNonNull(note, "note");
        if (anchorId == null || anchorId.isBlank()) {
            throw new IllegalArgumentException("anchorId must not be blank");
        }
    }
}
