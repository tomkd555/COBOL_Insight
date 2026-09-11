package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what the walk found into the units this run analyses.
 *
 * <p>{@code kinds} is what the subcommand looks at — {@code lint} has no use for JCL, {@code
 * sql-lint} none for BMS. {@code includeCopybookPaths} additionally collects copybooks from the
 * COPY search paths, which may point outside the asset folder; {@code fix} and {@code translate}
 * need those because they write a copy of every source they touch.
 *
 * <p>This is also where a {@code lint --scope} run narrows to what it was asked for. A scope leaves
 * out the COBOL programs outside it and nothing else: the copybooks, BMS mapsets, JCL members and
 * SQL scripts of the walk all stay in the run, because the programs inside the scope are analysed
 * against them. {@link ScopeFilter} then drops what the files outside the scope reported about
 * themselves.
 */
public record Classify(Set<AssetKind> kinds, boolean includeCopybookPaths) implements Step {

    @Override
    public void apply(SourceSet s) {
        Scope scope = s.options().scope();
        requireEveryScopeIsThere(s, scope);
        Map<String, SourceUnit> byRelPath = new LinkedHashMap<>();
        for (SourceDiscovery.DiscoveredFile file : s.discovery().filesOf(kinds)) {
            if (file.kind() == AssetKind.COBOL && !scope.contains(file.relPath())) {
                continue;
            }
            byRelPath.putIfAbsent(file.relPath(),
                    new SourceUnit(file.relPath(), file.absPath(), file.kind()));
        }
        if (includeCopybookPaths) {
            for (Path dir : s.copybookSearchPaths()) {
                for (Path copybook : CopybookScan.collect(dir)) {
                    String relPath = Paths.relativize(s.root(), copybook);
                    byRelPath.putIfAbsent(relPath,
                            new SourceUnit(relPath, copybook, AssetKind.COPYBOOK));
                }
            }
        }
        s.units().addAll(byRelPath.values().stream()
                .sorted(Comparator.comparing(SourceUnit::relPath)).toList());
    }

    /**
     * Stops a run whose scope names something the walk took no asset from. A folder that is there
     * but holds nothing to analyse is a different mistake from a folder that is not there, and the
     * user can only correct the one they made.
     */
    private static void requireEveryScopeIsThere(SourceSet s, Scope scope) {
        List<String> missing = scope.missing(s.discovery().files().stream()
                .map(SourceDiscovery.DiscoveredFile::relPath).toList());
        if (missing.isEmpty()) {
            return;
        }
        List<String> folders = missing.stream().filter(path -> isFolder(s.root(), path)).toList();
        List<String> absent = missing.stream().filter(path -> !isFolder(s.root(), path)).toList();
        StringBuilder message = new StringBuilder();
        if (!absent.isEmpty()) {
            message.append(String.join("、", absent))
                    .append(" は資産フォルダの中に見つかりませんでした。");
        }
        if (!folders.isEmpty()) {
            message.append(String.join("、", folders)).append(" には解析できる資産がありません。");
        }
        throw new Scope.NotFound(message.toString());
    }

    /** Whether a scope names a folder inside the asset folder, however little the walk took from it. */
    private static boolean isFolder(Path root, String path) {
        Path base = root.toAbsolutePath().normalize();
        try {
            Path resolved = base.resolve(path).normalize();
            return resolved.startsWith(base) && Files.isDirectory(resolved);
        } catch (InvalidPathException e) {
            return false;
        }
    }
}
