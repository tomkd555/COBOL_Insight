package jp.cobolinsight.transpile;

import jp.cobolinsight.core.transpile.GeneratedFile;
import jp.cobolinsight.core.transpile.TargetLanguage;
import jp.cobolinsight.core.transpile.TranspileResult;
import jp.cobolinsight.transpile.emit.Transpiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Confirms that all 9 generated Python sources (runtime helper, record classes, program) pass the
 * {@code py_compile} syntax check. Skips this check on environments where the Python interpreter is
 * not on PATH, so it does not break the gate there.
 */
class GeneratedPythonCompilesTest {

    private static final List<String> SAMPLES = List.of("SYK001.cbl", "SYK002.cbl", "SYK003.cbl",
            "SYK004.cbl", "SYK005.cbl", "SYK006.cbl", "SYK007.cbl", "SYK008.cbl", "SYK009.cbl");

    @Test
    void allSamplesGeneratedPythonCompiles(@TempDir Path tempDir) throws Exception {
        Optional<String> python = locatePython();
        assumeTrue(python.isPresent(), "Python インタプリタが PATH に無いため py_compile を省略する");

        for (String sample : SAMPLES) {
            TranspileResult result = Transpiler.transpile(SampleModels.model(sample),
                    SampleModels.sourceText(sample), TargetLanguage.PYTHON);
            Path dir = Files.createDirectories(tempDir.resolve(sample.replace('.', '_')));
            List<String> command = new ArrayList<>(List.of(python.get(), "-m", "py_compile"));
            for (GeneratedFile file : result.files()) {
                if (!file.fileName().endsWith(".py")) {
                    continue;
                }
                Path path = dir.resolve(file.fileName());
                Files.writeString(path, file.content(), StandardCharsets.UTF_8);
                command.add(path.toString());
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor(),
                    sample + " の生成 Python の py_compile 失敗:\n" + output);
        }
    }

    /** Looks for one of py, python, python3, in that order, for which {@code --version} succeeds. */
    private static Optional<String> locatePython() {
        for (String candidate : List.of("py", "python", "python3")) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                if (process.waitFor() == 0) {
                    return Optional.of(candidate);
                }
            } catch (IOException | InterruptedException e) {
                // Try the next candidate.
            }
        }
        return Optional.empty();
    }
}
