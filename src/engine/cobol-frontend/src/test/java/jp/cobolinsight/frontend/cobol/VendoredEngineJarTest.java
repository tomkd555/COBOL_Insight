package jp.cobolinsight.frontend.cobol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the in-tree Che4z artefact: the jar on disk must match its committed SHA-256, and the
 * classes this module compiles against must come from that jar rather than from a stray build.
 */
class VendoredEngineJarTest {

    private static final Path JAR_DIR = Path.of("..", "libs", "m2", "jp", "cobolinsight", "vendor",
            "che4z-cobol-engine", "2.5.1-ja1");

    @Test
    void jarMatchesCommittedSha256() throws Exception {
        Path jar = JAR_DIR.resolve("che4z-cobol-engine-2.5.1-ja1.jar");
        String expected = Files.readString(JAR_DIR.resolve("che4z-cobol-engine-2.5.1-ja1.jar.sha256")).strip();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        assertEquals(expected, HexFormat.of().formatHex(digest), "jar content drifted from .sha256");
    }

    @Test
    void engineClassesLoadFromTheVendoredJar() throws IOException {
        var location = org.eclipse.lsp.cobol.core.CobolLexer.class.getProtectionDomain()
                .getCodeSource().getLocation().getPath();
        assertTrue(location.endsWith("che4z-cobol-engine-2.5.1-ja1.jar"), location);
    }
}
