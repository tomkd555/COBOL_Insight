package jp.cobolinsight.app.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How long one file takes to lint, measured through the CLI entry point.
 *
 * <p>The GUI runs {@code lint --file} on every save, so this is the latency a user feels while
 * editing. The numbers are printed rather than asserted: a wall-clock threshold in a test fails on
 * a loaded build machine and tells nobody anything, whereas a printed figure is there to read when
 * someone suspects a regression. The only assertion is that the run succeeded.
 */
class ColdStartBenchmarkTest {

    private static final Path SAMPLES =
            Path.of("..", "..", "..", "samples").toAbsolutePath().normalize();

    private static final int RUNS = 3;

    @Test
    void lintOfASingleFile(@TempDir Path tempDir) {
        List<Long> millis = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) {
            long startedAt = System.nanoTime();
            int exitCode = new CommandLine(new Main()).execute("lint", SAMPLES.toString(),
                    "--file", SAMPLES.resolve("cobol").resolve("SYK001.cbl").toString(),
                    "--copybook-path", SAMPLES.resolve("copybook").toString(),
                    "--sarif", tempDir.resolve("run" + run + ".sarif").toString());
            millis.add((System.nanoTime() - startedAt) / 1_000_000);

            // 0 = nothing found, 1 = warnings, 2 = errors. All three mean the run itself worked.
            assertTrue(exitCode >= 0 && exitCode <= 2, "lint --file failed with " + exitCode);
        }
        System.out.printf(Locale.ROOT, "lint --file SYK001.cbl: %s ms (first run is the cold one)%n",
                millis);
    }
}
