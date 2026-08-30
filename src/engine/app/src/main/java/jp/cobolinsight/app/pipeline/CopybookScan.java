package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collects copybooks directly under the copybook search path. Used to obtain the targets that
 * translate and fix copy alongside the original.
 *
 * <p>This is a <b>separate concern</b> from scanning the input folder ({@link SourceDiscovery}).
 * That one infers kind from content regardless of where the asset lives, whereas this is the
 * layer that collects COPY-statement resolution targets, picked up by extension. Because COBOL's
 * COPY resolution builds a filename from "copybook name + extension", matching the extension is
 * exactly what determines whether it can be resolved. Every extension that
 * {@link AssetKind#COPYBOOK} claims is accepted.
 */
final class CopybookScan {

    private CopybookScan() {
    }

    /**
     * Returns the copybooks directly under the search path, sorted by filename ascending. Returns
     * empty if the path is not a directory. Because the search path can point outside the input
     * folder, this looks only at direct children with no recursion (COPY resolution does the same).
     */
    static List<Path> collect(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> AssetKind.ofFileName(p.getFileName().toString())
                            == AssetKind.COPYBOOK)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
