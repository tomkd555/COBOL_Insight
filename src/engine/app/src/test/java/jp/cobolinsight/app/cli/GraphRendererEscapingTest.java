package jp.cobolinsight.app.cli;

import jp.cobolinsight.core.callgraph.CallGraph;
import jp.cobolinsight.core.callgraph.CallGraphEdge;
import jp.cobolinsight.core.callgraph.CallGraphNode;
import jp.cobolinsight.core.callgraph.EdgeKind;
import jp.cobolinsight.core.callgraph.NodeKind;
import jp.cobolinsight.core.callgraph.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A regression test for SVG/PNG generation. Verifies that passing DOT with node labels containing
 * single quotes, backslashes, control characters, and newlines to the JS engine (viz.js) still
 * completes generation without breaking a JS string boundary.
 */
class GraphRendererEscapingTest {

    @TempDir
    Path tempDir;

    @Test
    void rendersSvgAndPngForLabelsWithQuotesBackslashesControlCharsAndNewlines()
            throws IOException {
        CallGraphNode from = new CallGraphNode("program:PGM'A", NodeKind.PROGRAM,
                "PGM'A \\back\\slash");
        CallGraphNode to = new CallGraphNode("program:PGMB", NodeKind.PROGRAM,
                "line1\nline2\ttab\"quote\"");
        CallGraph graph = new CallGraph(List.of(from, to), List.of(
                new CallGraphEdge(from.id(), to.id(), EdgeKind.CALL, Resolution.CONSTANT)));
        String dot = graph.toDot();

        Path svg = tempDir.resolve("escape.svg");
        GraphRenderer.writeSvg(dot, svg);
        String svgText = Files.readString(svg, StandardCharsets.UTF_8);
        assertTrue(svgText.contains("<svg"), "特殊文字入りラベルでもSVGが生成されること");

        Path png = tempDir.resolve("escape.png");
        GraphRenderer.writePng(dot, png);
        assertTrue(Files.size(png) > 0, "特殊文字入りラベルでもPNGが生成されること");
    }
}
