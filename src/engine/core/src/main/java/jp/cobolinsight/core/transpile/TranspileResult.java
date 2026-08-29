package jp.cobolinsight.core.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;

import java.util.List;
import java.util.Objects;

/**
 * 1プログラムを1対象言語へ逐語対訳した結果。生成ファイル群と、COBOL行と生成行の対応表を持つ。
 * 複数プログラム・複数言語を束ねる場合は本型のリストで表す。
 */
public record TranspileResult(String programId, TargetLanguage language,
        List<GeneratedFile> files, List<LineMappingEntry> lineMap) {

    public TranspileResult {
        if (programId == null || programId.isBlank()) {
            throw new IllegalArgumentException("programId must not be blank");
        }
        Objects.requireNonNull(language, "language");
        files = List.copyOf(files);
        lineMap = List.copyOf(lineMap);
    }
}
