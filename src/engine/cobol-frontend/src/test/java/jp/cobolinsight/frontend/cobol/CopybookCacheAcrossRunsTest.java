package jp.cobolinsight.frontend.cobol;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.spi.ParseOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One program analysed twice in the same process, the first time under a search path that cannot
 * reach its copybook. Che4z caches the copybook it resolved — and the failure to resolve one —
 * under the copybook's name and the program's URI, with no trace of the search path it looked in,
 * so without the invalidation in {@link Che4zRuntime} the second analysis is answered out of the
 * first one's cache and the program loses its whole semantic model. Two analyses that need
 * different copybook search paths in one process are exactly that shape.
 */
class CopybookCacheAcrossRunsTest {

    private static final String COPYBOOK =
            "      *  Only the second analysis can reach this copybook\n"
            + "       01  REC-ROOT.\n"
            + "           05  REC-FIELD              PIC X(10).\n";

    private static final String PROGRAM =
            "       IDENTIFICATION DIVISION.\n"
            + "       PROGRAM-ID.  CPYCACHE.\n"
            + "       DATA DIVISION.\n"
            + "       WORKING-STORAGE SECTION.\n"
            + "           COPY CPYCACH1.\n"
            + "       PROCEDURE DIVISION.\n"
            + "           MOVE SPACE TO REC-FIELD.\n"
            + "           STOP RUN.\n";

    @Test
    void theRunThatCanReachTheCopybookSucceedsAfterOneThatCannot(@TempDir Path dir)
            throws IOException {
        Files.writeString(dir.resolve("CPYCACH1.cpy"), COPYBOOK, StandardCharsets.UTF_8);
        Path program = dir.resolve("CPYCACHE.cbl");
        Files.writeString(program, PROGRAM, StandardCharsets.UTF_8);
        Che4zCobolParser parser = new Che4zCobolParser();

        ParseOutcome<CobolSemanticModel> without =
                parser.parse(TestSources.load(program), List.of());
        assertTrue(without.failureFinding().isPresent(),
                "with no search path the copybook is out of reach and the program fails");

        ParseOutcome<CobolSemanticModel> with =
                parser.parse(TestSources.load(program), List.of(dir));
        assertTrue(with.value().isPresent(), "the second analysis reaches the copybook: "
                + with.failureFinding().map(Object::toString).orElse(""));
    }

    /**
     * The other half of the same rule: while the search paths stay as they were, the cache stands,
     * because no entry in it can answer for a different set of copybooks. Deleting the copybook
     * between the two analyses is what makes that observable — the second one succeeds out of the
     * cache, and fails as soon as a changed search path drops it.
     */
    @Test
    void analysesUnderTheSameSearchPathsShareTheCache(@TempDir Path dir) throws IOException {
        Path copybook = dir.resolve("CPYKEEP1.cpy");
        Files.writeString(copybook, COPYBOOK, StandardCharsets.UTF_8);
        Path program = dir.resolve("CPYKEEP.cbl");
        Files.writeString(program, PROGRAM.replace("CPYCACHE", "CPYKEEP")
                .replace("CPYCACH1", "CPYKEEP1"), StandardCharsets.UTF_8);
        Che4zCobolParser parser = new Che4zCobolParser();

        assertTrue(parser.parse(TestSources.load(program), List.of(dir)).value().isPresent(),
                "the copybook is in reach on the first analysis");
        Files.delete(copybook);

        ParseOutcome<CobolSemanticModel> cached =
                parser.parse(TestSources.load(program), List.of(dir));
        assertTrue(cached.value().isPresent(), "the same search paths keep the cached copybook: "
                + cached.failureFinding().map(Object::toString).orElse(""));

        ParseOutcome<CobolSemanticModel> afterChange =
                parser.parse(TestSources.load(program), List.of(dir.resolve("elsewhere")));
        assertTrue(afterChange.failureFinding().isPresent(),
                "a changed search path drops the cache, and the copybook is gone");
    }
}
