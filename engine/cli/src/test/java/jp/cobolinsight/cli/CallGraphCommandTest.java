package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `callgraph` サブコマンドのJSON/DOT/SVG/PNG出力の検証。 */
class CallGraphCommandTest {

    private static final Path SAMPLES = Path.of("..", "..", "samples").toAbsolutePath().normalize();

    @TempDir
    Path tempDir;

    @Test
    void writesJsonDotSvgAndPngFiles() throws IOException {
        Path json = tempDir.resolve("graph.json");
        Path dot = tempDir.resolve("graph.dot");
        Path svg = tempDir.resolve("graph.svg");
        Path png = tempDir.resolve("graph.png");

        int exitCode = new CommandLine(new Main()).execute("callgraph", SAMPLES.toString(),
                "--db", tempDir.resolve("m2.db").toString(),
                "--json", json.toString(), "--dot", dot.toString(),
                "--svg", svg.toString(), "--png", png.toString());

        assertEquals(0, exitCode);

        String jsonText = Files.readString(json, StandardCharsets.UTF_8);
        assertTrue(jsonText.contains("\"id\":\"program:SYK001\""), jsonText.substring(0, 200));
        assertTrue(jsonText.contains("\"kind\":\"TRANSACTION_TRANSITION\""));

        String dotText = Files.readString(dot, StandardCharsets.UTF_8);
        assertTrue(dotText.startsWith("digraph callgraph {"));
        assertTrue(dotText.contains("\"program:SYK002\" -> \"program:SYK004\""));

        // SVG/PNGはgraphviz-java(JVM内)のみで生成される(外部ネイティブバイナリ不要)
        String svgText = Files.readString(svg, StandardCharsets.UTF_8);
        assertTrue(svgText.contains("<svg"), "SVGが生成されること");
        assertTrue(svgText.contains("SYK001"), "SVGにノードラベルが含まれること");

        byte[] pngBytes = Files.readAllBytes(png);
        assertArrayEquals(new byte[] {(byte) 0x89, 'P', 'N', 'G'},
                new byte[] {pngBytes[0], pngBytes[1], pngBytes[2], pngBytes[3]},
                "PNGシグネチャを持つこと");
    }
}
