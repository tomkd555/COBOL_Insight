package jp.cobolinsight.app.pipeline;

/** Walks the asset folder. */
public final class Discover implements Step {

    @Override
    public void apply(SourceSet s) {
        s.discovery(SourceDiscovery.discover(s.root()));
    }
}
