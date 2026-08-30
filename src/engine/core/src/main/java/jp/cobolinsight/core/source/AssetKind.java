package jp.cobolinsight.core.source;

import java.util.List;
import java.util.Locale;

/**
 * The kinds of assets to analyze, and the canonical set of extensions for each kind.
 *
 * <p>The mapping between extensions and kinds lives in this one place only. Growing one side
 * without the other would leave the scan target, the user-defined-rule target, and the copybook
 * collection each looking at a different table.
 *
 * <p>When scanning the input folder, the source content decides the kind; the extension is only
 * a fallback (a clue used when the content does not decide it). Collection under a copybook
 * search path, by contrast, is a separate concern of gathering COPY statement resolution
 * targets, and there the extension does matter: COBOL COPY resolution builds the file name as
 * "copybook name + extension", so an extension match is itself what makes resolution possible.
 */
public enum AssetKind {
    /** A BMS map definition. */
    BMS(".bms"),
    /** COBOL source. */
    COBOL(".cbl", ".cob", ".cobol"),
    /** A copybook. */
    COPYBOOK(".cpy", ".copy"),
    /** JCL. */
    JCL(".jcl");

    private final List<String> extensions;

    AssetKind(String... extensions) {
        this.extensions = List.of(extensions);
    }

    /** The extensions this kind claims (lowercase, including the leading dot). */
    public List<String> extensions() {
        return extensions;
    }

    /**
     * Looks up the kind from an extension. Leading-dot presence and case are ignored.
     * Returns null for an extension that belongs to no kind.
     */
    public static AssetKind ofExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return null;
        }
        String normalized = extension.startsWith(".")
                ? extension.toLowerCase(Locale.ROOT)
                : "." + extension.toLowerCase(Locale.ROOT);
        for (AssetKind kind : values()) {
            if (kind.extensions.contains(normalized)) {
                return kind;
            }
        }
        return null;
    }

    /**
     * Looks up the kind from a file name's extension. Returns null for a name with no extension
     * and for an extension that belongs to no kind. Given a path, only the trailing name is used.
     */
    public static AssetKind ofFileName(String fileName) {
        return ofExtension(extensionOf(fileName));
    }

    /**
     * The extension of a file name (lowercase, including the leading dot). Returns an empty
     * string for a name with no extension. A name that starts with a dot (such as .gitignore)
     * is treated as having no extension.
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.substring(Math.max(fileName.lastIndexOf('/'),
                fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
