package jp.cobolinsight.app.cli;

import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizJdkEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Generates SVG/PNG from DOT text. This is entirely self-contained via graphviz-java (viz.js
 * running in-JVM), and requires no external native Graphviz binary.
 */
final class GraphRenderer {

    static {
        // Running viz.js is handled by GraalJS bundled on the classpath (Nashorn is unused with the JDK 21 in this configuration)
        Graphviz.useEngine(new GraphvizJdkEngine());
    }

    private GraphRenderer() {
    }

    static void writeSvg(String dot, Path output) {
        render(dot, Format.SVG, output);
    }

    static void writePng(String dot, Path output) {
        render(dot, Format.PNG, output);
    }

    private static void render(String dot, Format format, Path output) {
        try {
            Graphviz.fromString(dot).render(format).toFile(output.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
