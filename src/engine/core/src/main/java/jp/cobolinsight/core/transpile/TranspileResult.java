package jp.cobolinsight.core.transpile;

import jp.cobolinsight.core.linemap.LineMappingEntry;

import java.util.List;
import java.util.Objects;

/**
 * The result of translating one program into one target language line by line. Holds the
 * generated files and a mapping between COBOL lines and generated lines. When bundling multiple
 * programs or languages, represent them as a list of this type.
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
