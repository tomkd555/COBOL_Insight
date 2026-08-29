package jp.cobolinsight.app.pipeline;

/** One stage of the pipeline. A step reads what earlier steps left in the set and adds its own. */
public interface Step {

    void apply(SourceSet s);
}
