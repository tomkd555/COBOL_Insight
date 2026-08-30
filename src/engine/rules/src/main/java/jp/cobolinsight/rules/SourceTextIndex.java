package jp.cobolinsight.rules;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Index of decoded source text referenced during rule evaluation. The keys are the same path
 * strings as sourceFile in the semantic model, and this is passed around as an artifact of
 * AnalysisContext. Used by syntactic rules that need lexical information not present in the
 * semantic model (section headers, COPY statements, string literals).
 */
public final class SourceTextIndex {

    private final Map<String, String> textByPath;

    public SourceTextIndex(Map<String, String> textByPath) {
        this.textByPath = new TreeMap<>(textByPath);
    }

    /** Looks up text by exact match of the path string. */
    public Optional<String> textOf(String path) {
        return Optional.ofNullable(textByPath.get(path));
    }

    /**
     * Looks up text by a case-insensitive match of the file name's base name (excluding the
     * extension). Used to resolve COPY statement copybook names. If multiple matches exist with
     * different content, returns empty.
     */
    public Optional<String> textOfBaseName(String baseName) {
        String wanted = baseName.toUpperCase(Locale.ROOT);
        String found = null;
        for (Map.Entry<String, String> entry : textByPath.entrySet()) {
            Path fileName = Path.of(entry.getKey()).getFileName();
            if (fileName == null) {
                continue;
            }
            String name = fileName.toString();
            int dot = name.lastIndexOf('.');
            String base = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
            if (base.equals(wanted)) {
                if (found != null && !found.equals(entry.getValue())) {
                    return Optional.empty();
                }
                found = entry.getValue();
            }
        }
        return Optional.ofNullable(found);
    }

    /** All registered entries (in ascending path order). */
    public Map<String, String> entries() {
        return java.util.Collections.unmodifiableMap(textByPath);
    }
}
