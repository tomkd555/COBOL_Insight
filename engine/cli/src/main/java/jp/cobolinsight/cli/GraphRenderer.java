package jp.cobolinsight.cli;

import guru.nidi.graphviz.engine.Format;
import guru.nidi.graphviz.engine.Graphviz;
import guru.nidi.graphviz.engine.GraphvizJdkEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * DOTテキストからのSVG/PNG生成。graphviz-java(viz.jsのJVM内実行)のみで完結し、
 * 外部のネイティブGraphvizバイナリを要しない。
 */
final class GraphRenderer {

    static {
        // viz.jsの実行はクラスパス同梱のGraalJSが担う(本構成のJDK 21ではNashornは未使用)
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
