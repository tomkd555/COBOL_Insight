package jp.cobolinsight.app.cli;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves programs that import a copybook via a COPY statement. A fix originating from a copybook
 * leaves the original copybook unmodified and stays at the diff-preview stage, so the impact scope
 * must list every program that imports that copybook alongside it. This determines that list
 * deterministically by scanning decoded program sources for COPY statements.
 */
final class CopybookImporters {

    private CopybookImporters() {
    }

    /**
     * Returns, in ascending order, the relative paths of programs that import {@code copybookBaseName}
     * (the base name without extension, case-insensitive) via a COPY statement. {@code programSourcesByRel}
     * maps a program's relative path to its decoded source text. COPY statements with REPLACING are also covered.
     */
    static List<String> of(String copybookBaseName, Map<String, String> programSourcesByRel) {
        // If the character immediately after is a COBOL word-constituent character (alphanumeric,
        // hyphen, underscore, $, #), it is only a prefix match of a longer alias, so exclude it.
        Pattern copy = Pattern.compile(
                "(?i)\\bCOPY\\s+" + Pattern.quote(copybookBaseName) + "(?![\\p{L}\\p{N}$#_-])");
        return programSourcesByRel.entrySet().stream()
                .filter(entry -> copy.matcher(entry.getValue()).find())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Extracts the extension-less base name (the copybook name) from a relative path. */
    static String baseName(String relPath) {
        String name = relPath;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
