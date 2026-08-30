package jp.cobolinsight.frontend.cobol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins down that copybook resolution tries every extension AssetKind.COPYBOOK declares. */
class SearchPathClientTest {

    @Test
    void resolvesCopybookWithCopyExtension(@TempDir Path dir) throws IOException {
        Path copybook = write(dir, "CUSTREC.copy");

        assertEquals(copybook.toUri().toString(), resolve(dir, "CUSTREC"));
    }

    @Test
    void resolvesCopybookWithCpyExtension(@TempDir Path dir) throws IOException {
        Path copybook = write(dir, "CUSTREC.cpy");

        assertEquals(copybook.toUri().toString(), resolve(dir, "CUSTREC"));
    }

    @Test
    void returnsNullWhenNoCandidateExists(@TempDir Path dir) {
        assertNull(resolve(dir, "CUSTREC"));
    }

    private static Path write(Path dir, String fileName) throws IOException {
        return Files.writeString(dir.resolve(fileName), "       01 CUST-REC.\n",
                StandardCharsets.UTF_8);
    }

    private static String resolve(Path searchPath, String copybookName) {
        SearchPathClient client = new SearchPathClient();
        client.setSearchPaths(List.of(searchPath));
        return client.resolveCopybookUri(searchPath.toUri().toString(), copybookName, null).join();
    }
}
