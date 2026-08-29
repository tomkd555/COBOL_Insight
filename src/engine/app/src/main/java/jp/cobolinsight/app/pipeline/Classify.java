package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.source.AssetKind;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Turns what the walk found into the units this run analyses.
 *
 * <p>{@code kinds} is what the subcommand looks at — {@code lint} has no use for JCL, {@code
 * sql-lint} none for BMS. {@code includeCopybookPaths} additionally collects copybooks from the
 * COPY search paths, which may point outside the asset folder; {@code fix} and {@code translate}
 * need those because they write a copy of every source they touch.
 */
public record Classify(Set<AssetKind> kinds, boolean includeCopybookPaths) implements Step {

    @Override
    public void apply(SourceSet s) {
        Map<String, SourceUnit> byRelPath = new LinkedHashMap<>();
        for (SourceDiscovery.DiscoveredFile file : s.discovery().filesOf(kinds)) {
            byRelPath.putIfAbsent(file.relPath(),
                    new SourceUnit(file.relPath(), file.absPath(), file.kind()));
        }
        if (includeCopybookPaths) {
            for (Path dir : s.options().copybookSearchPaths()) {
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
}
