package jp.cobolinsight.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the jpackage app-image (not the build JVM) against the EBCDIC samples. The image's runtime
 * is assembled by jlink from the modules named in build.gradle.kts; if jdk.charsets is missing there,
 * x-IBM930/x-IBM939 are absent and only this test notices. Skipped when the image has not been built.
 */
class PackagedImageCharsetTest {

    private static final Path IMAGE_EXE = Path.of("build", "jpackage", "app-image", "COBOLInsight", "COBOLInsight.exe");
    private static final Path SAMPLES = Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    static boolean imageExists() {
        return Files.exists(IMAGE_EXE);
    }

    @Test
    @EnabledIf("imageExists")
    void packagedImageDecodesEbcdicSamples() throws IOException, InterruptedException {
        Path work = Files.createTempDirectory("packaged-image");
        Path db = work.resolve("cobol-insight.db");
        Process p = new ProcessBuilder(IMAGE_EXE.toAbsolutePath().toString(),
                "scan", SAMPLES.resolve("encoding").toString(), "--db", db.toString())
                .directory(work.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(5, TimeUnit.MINUTES), "scan did not finish");
        assertEquals(0, p.exitValue(), out);
        assertTrue(Files.exists(db), out);
        assertTrue(out.contains("\"analyzed\":[\"SYKENC1_CP930.cbl\",\"SYKENC1_CP939.cbl\",\"SYKENC1_SJIS.cbl\",\"SYKENC1_UTF8.cbl\"]"), out);
        assertTrue(out.contains("\"unreadable\":[]"), out);
    }
}
