package jp.cobolinsight.app.pipeline;

import java.util.List;

/** Runs steps in order over one source set. */
public final class Pipeline {

    private Pipeline() {
    }

    public static SourceSet run(List<Step> steps, SourceSet seed) {
        for (Step step : steps) {
            step.apply(seed);
        }
        return seed;
    }
}
