package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.nio.file.Path;

/**
 * One asset the pipeline works on. {@code relPath} uses forward slashes and is relative to the
 * asset folder, except for copybooks found outside it, which keep "parent directory/file name".
 */
public record SourceUnit(String relPath, Path absPath, AssetKind kind) {

    public String fileName() {
        return absPath.getFileName().toString();
    }
}
