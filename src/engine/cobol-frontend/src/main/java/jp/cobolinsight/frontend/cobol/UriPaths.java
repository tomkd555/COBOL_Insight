package jp.cobolinsight.frontend.cobol;

import java.net.URI;
import java.nio.file.Paths;

/** Converts between a URI and a file-path representation. */
final class UriPaths {

    private UriPaths() {
    }

    /**
     * Is this the URI of code Che4z implicitly inserts (such as SQLCA)? Since it has no real
     * file, it is excluded from original-text retrieval and the copybook expansion mapping.
     */
    static boolean isImplicit(String uri) {
        return uri == null || uri.startsWith("implicit:") || uri.contains("implicit-code");
    }

    /** Converts a file URI to a filesystem path string. Returns the URI unchanged if it cannot be converted. */
    static String toPathString(String uri) {
        try {
            return Paths.get(new URI(uri)).toString();
        } catch (Exception e) {
            return uri;
        }
    }
}
