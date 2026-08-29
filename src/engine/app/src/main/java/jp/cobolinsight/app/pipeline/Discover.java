package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.nio.file.Path;
import java.util.List;

/**
 * Walks the asset folder. When the run is scoped to a single file, that file is the whole walk;
 * its copybooks still come from the search paths, which {@link Classify} adds.
 */
public final class Discover implements Step {

    @Override
    public void apply(SourceSet s) {
        Path single = s.options().singleFile();
        if (single == null) {
            s.discovery(SourceDiscovery.discover(s.root()));
            return;
        }
        Path abs = single.toAbsolutePath().normalize();
        AssetKind kind = SourceClassifier.classify(Paths.readBytes(abs)).kind();
        if (kind == null) {
            kind = AssetKind.ofFileName(abs.getFileName().toString());
        }
        if (kind == null) {
            s.discovery(new SourceDiscovery.Result(List.of(), List.of(), false,
                    List.of(Paths.relativize(s.root(), abs)), List.of(), List.of()));
            return;
        }
        s.discovery(new SourceDiscovery.Result(
                List.of(new SourceDiscovery.DiscoveredFile(Paths.relativize(s.root(), abs), abs,
                        kind, SourceDiscovery.Evidence.CONTENT)),
                List.of(), false, List.of(), List.of(), List.of()));
    }
}
