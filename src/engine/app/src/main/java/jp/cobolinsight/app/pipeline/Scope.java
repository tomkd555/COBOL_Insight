package jp.cobolinsight.app.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The part of the asset folder one run analyses, as {@code lint --scope} names it: paths relative
 * to INPUT_DIR, each naming a file or a directory inside it.
 *
 * <p>The scope narrows which COBOL programs are parsed and which findings are reported, never the
 * walk. INPUT_DIR stays the root, so the relative paths of the SARIF, the summary and every message
 * are the same as in a whole-folder run; every other kind of asset stays in the run, so a copybook,
 * a BMS mapset or a PROC member outside the scope still answers for the programs inside it.
 *
 * <p>A path is matched with {@code /} as the separator whichever the user wrote, and without
 * regard to case: this tool runs on Windows, where the same file answers to either spelling. The
 * spellings the user wrote are what is kept, because they are what every message names back.
 *
 * <p>Both forms are held: matching runs once per walked file and once per finding, so the
 * normalised form is computed here, once, rather than on each call.
 */
public final class Scope {

    /** The whole folder: what every subcommand but a scoped {@code lint} runs with. */
    public static final Scope ALL = new Scope(List.of());

    private final List<String> written;
    private final List<String> paths;

    /** Raised when a scope names nothing the walk found. Handled as a usage error by the CLI. */
    public static final class NotFound extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    /** @param written the scopes as the user wrote them, minus any that name the whole folder */
    public Scope(List<String> written) {
        // A scope that normalises away — "", ".", "/" — is the whole folder, which is no scope.
        this.written = written.stream().map(String::trim)
                .filter(path -> !normalise(path).isEmpty()).toList();
        this.paths = this.written.stream().map(Scope::normalise).toList();
    }

    /** The scopes as the user wrote them. */
    public List<String> written() {
        return written;
    }

    /** The scopes in the form matching reads them, in the order they were written. */
    public List<String> paths() {
        return paths;
    }

    public boolean isEmpty() {
        return written.isEmpty();
    }

    /** Whether one walked file is analysed: it is the scope itself, or it lies under one. */
    public boolean contains(String relPath) {
        if (written.isEmpty()) {
            return true;
        }
        String candidate = normalise(relPath);
        return paths.stream().anyMatch(path -> covers(path, candidate));
    }

    /**
     * The scopes the walk found nothing for, as the user wrote them. A scope that names nothing is
     * the user's mistake — the run would analyse less than they asked for, and silently — so the
     * caller stops the run over it.
     */
    public List<String> missing(Collection<String> walkedRelPaths) {
        if (written.isEmpty()) {
            return List.of();
        }
        List<String> walked = walkedRelPaths.stream().map(Scope::normalise).toList();
        List<String> missing = new ArrayList<>();
        for (String path : written) {
            String scope = normalise(path);
            if (walked.stream().noneMatch(rel -> covers(scope, rel))) {
                missing.add(path);
            }
        }
        return missing;
    }

    private static boolean covers(String scope, String relPath) {
        return relPath.equals(scope) || relPath.startsWith(scope + "/");
    }

    /**
     * Both separators, no leading {@code ./} and no surrounding {@code /}, lower case. A path that
     * names the folder itself — {@code .} as much as the empty string — normalises to empty.
     */
    private static String normalise(String path) {
        String slashed = path.replace('\\', '/').trim();
        while (slashed.startsWith("./")) {
            slashed = slashed.substring(2);
        }
        while (slashed.startsWith("/")) {
            slashed = slashed.substring(1);
        }
        while (slashed.endsWith("/")) {
            slashed = slashed.substring(0, slashed.length() - 1);
        }
        return ".".equals(slashed) ? "" : slashed.toLowerCase(Locale.ROOT);
    }
}
