package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.source.CopyExpansionEntry;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Keeps only the findings a {@code lint --scope} run was asked for.
 *
 * <p>A scope leaves the COBOL outside it unparsed and nothing else, so that the programs inside it
 * are analysed against the whole estate: the copybooks, BMS mapsets, JCL members and SQL scripts of
 * the walk are all in the run. What those files report about themselves is another matter — the
 * user asked about the scope — so it is dropped here, once every rule has had the whole walk to
 * work with. The pipeline's own findings go the same way as the rules'.
 *
 * <p>Two kinds of file outside the scope keep their findings, because they are read as part of a
 * file inside it: a copybook an in-scope program expands, and a PROC or INCLUDE member an in-scope
 * job expands. A copybook no in-scope program copies is dropped like anything else — rules that
 * read the source text of every decoded unit report on those too, and the user asked about the
 * scope. So is a member reached through {@code --proc-path}: it stands outside the asset folder,
 * and the user scoped a part of that folder.
 */
public final class ScopeFilter implements Step {

    @Override
    public void apply(SourceSet s) {
        Scope scope = s.options().scope();
        if (scope.isEmpty()) {
            return;
        }
        Map<Path, String> relByAbs = s.relPathByAbsPath();
        Set<String> expanded = expandedInsideTheScope(s, scope);
        Predicate<Finding> outsideTheScope = finding -> {
            String relPath = relPathOf(finding.location().file(), relByAbs, s.root());
            return !scope.contains(relPath) && !expanded.contains(relPath);
        };
        s.findings().removeIf(outsideTheScope);
        s.ruleFindings(Command.LINT).removeIf(outsideTheScope);
        s.ruleFindings(Command.SQL_LINT).removeIf(outsideTheScope);
    }

    /**
     * The copybooks and the PROC or INCLUDE members the files inside the scope expanded, named the
     * way {@link #relPathOf} names a finding's file. A member the walk never saw is left out: it
     * is not part of the asset folder the scope divides.
     */
    private static Set<String> expandedInsideTheScope(SourceSet s, Scope scope) {
        Set<String> expanded = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        s.programsByPath().forEach((relPath, model) -> {
            if (!scope.contains(relPath)) {
                return;
            }
            // Every expansion, not the inline ones alone: a copybook a copybook copies is read
            // for the program all the same.
            for (CopyExpansionEntry expansion : model.copyExpansions()) {
                expanded.add(Paths.relativizeOrAbsolute(s.root(),
                        Path.of(expansion.copybookPath())));
            }
        });
        Map<Path, String> relByAbs = s.relPathByAbsPath();
        s.jobsByPath().forEach((relPath, jobs) -> {
            if (!scope.contains(relPath)) {
                return;
            }
            for (JclJobModel job : jobs) {
                for (String member : job.members()) {
                    String memberPath = relPathOf(member, relByAbs, s.root());
                    // An absolute path is a member of a --proc-path library, which stands outside
                    // the asset folder the scope divides.
                    if (!Path.of(memberPath).isAbsolute()) {
                        expanded.add(memberPath);
                    }
                }
            }
        });
        return expanded;
    }

    /**
     * What the asset folder calls a finding's file. A file the walk never saw — a copybook or a
     * member of a search path — keeps the absolute path it was read from, which no scope covers.
     */
    private static String relPathOf(String file, Map<Path, String> relByAbs, Path root) {
        Path path;
        try {
            path = Path.of(file);
        } catch (InvalidPathException e) {
            return file;
        }
        // Decode failures are located by the path the asset folder knows, not by an absolute one.
        if (!path.isAbsolute()) {
            return file.replace('\\', '/');
        }
        Path absolute = path.toAbsolutePath().normalize();
        String relPath = relByAbs.get(absolute);
        return relPath != null ? relPath : Paths.relativizeOrAbsolute(root, absolute);
    }
}
