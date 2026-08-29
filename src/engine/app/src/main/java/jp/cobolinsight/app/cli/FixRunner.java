package jp.cobolinsight.app.cli;

import jp.cobolinsight.app.pipeline.Pipelines;
import jp.cobolinsight.app.pipeline.SourceSet;
import jp.cobolinsight.app.pipeline.SourceUnit;
import jp.cobolinsight.core.encoding.CodePage;
import jp.cobolinsight.core.encoding.SourceDecoder;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.finding.FixSuggestion;
import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.fix.ByteSpliceApplier;
import jp.cobolinsight.core.rule.Command;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.rules.RuleSet;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * {@code fix}. Evaluates the rules that carry a canned fix, collects their edits per original file
 * and splices them into the bytes of that file.
 *
 * <p>Editing is done on bytes rather than characters: a fixed-format source keeps its columns only
 * if the code page stays out of the way. Insertions are already laid out in fixed format by the
 * rule's fix producer; this runner only applies them in ascending, non-overlapping order, and the
 * applier rejects an overlap.
 *
 * <p>Original files are never written. {@code fix preview} shows a diff and {@code fix apply}
 * writes to an output folder.
 */
public final class FixRunner {

    public record Options(Path inputDir, List<Path> copybookSearchPaths,
            Map<String, String> codepageOverrides, RuleSet ruleSet) {

        public Options {
            copybookSearchPaths = List.copyOf(copybookSearchPaths);
            codepageOverrides = Map.copyOf(codepageOverrides);
        }

        /** The default rule set: every built-in rule, no configuration file. */
        public Options(Path inputDir, List<Path> copybookSearchPaths,
                Map<String, String> codepageOverrides) {
            this(inputDir, copybookSearchPaths, codepageOverrides, RuleSet.load((Path) null));
        }
    }

    /**
     * One file's fix: its relative path, settled code page, original and fixed text, the fixed
     * bytes (already encoded in the original's code page) and the descriptions applied.
     *
     * <p>When {@code copybook} is true the fix came from a copybook. A copybook is expanded into
     * several programs, so its original is left alone and only shown as a diff, with
     * {@code importers} naming the programs that copy it, in ascending order. For a program's own
     * fix, {@code copybook} is false and {@code importers} empty.
     */
    public record FileFix(String relPath, String charsetName, String originalText, String fixedText,
            byte[] fixedBytes, List<String> descriptions, boolean copybook,
            List<String> importers) {

        public FileFix {
            descriptions = List.copyOf(descriptions);
            importers = List.copyOf(importers);
        }
    }

    public record Result(List<FileFix> fileFixes, List<Finding> analysisFindings, int fixCount) {

        public Result {
            fileFixes = List.copyOf(fileFixes);
            analysisFindings = List.copyOf(analysisFindings);
        }

        public long analysisErrors() {
            return analysisFindings.stream().filter(f -> f.level() == FindingLevel.ERROR).count();
        }
    }

    private final SourceDecoder sourceDecoder = new SourceDecoder();
    private final ByteSpliceApplier applier = new ByteSpliceApplier();

    public Result run(Options options) {
        SourceSet s = Pipelines.fix(options.inputDir(), options.copybookSearchPaths(),
                options.codepageOverrides(), options.ruleSet());
        LintRunner.reportWarnings(s);

        Map<String, SourceUnit> unitBySourcePath = new LinkedHashMap<>();
        Map<String, String> programSourcesByRel = new LinkedHashMap<>();
        for (SourceUnit unit : s.units()) {
            DecodedSource decoded = s.decoded().get(unit.relPath());
            if (decoded == null) {
                continue;
            }
            unitBySourcePath.put(decoded.path(), unit);
            if (unit.kind() == AssetKind.COBOL) {
                programSourcesByRel.put(unit.relPath(), decoded.text());
            }
        }

        // A rule's finding is the raw material of a fix; its severity says nothing about whether
        // the fix worked, so it stays out of analysisFindings. That list holds only what the
        // pipeline could not decode or parse — the sources it could not analyse at all.
        Map<String, List<TextEdit>> editsBySource = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> descriptionsBySource = new LinkedHashMap<>();
        for (Finding finding : s.ruleFindings(Command.FIX)) {
            for (FixSuggestion suggestion : finding.fixes()) {
                String source = finding.location().file();
                editsBySource.computeIfAbsent(source, key -> new ArrayList<>())
                        .addAll(suggestion.edits());
                descriptionsBySource.computeIfAbsent(source, key -> new LinkedHashSet<>())
                        .add(suggestion.description());
            }
        }

        List<FileFix> fileFixes = new ArrayList<>();
        int fixCount = 0;
        for (Map.Entry<String, List<TextEdit>> entry : editsBySource.entrySet()) {
            SourceUnit unit = unitBySourcePath.get(entry.getKey());
            if (unit == null) {
                continue;
            }
            List<TextEdit> edits = entry.getValue();
            byte[] bytes = s.bytes().get(unit.relPath());
            String override = options.codepageOverrides().get(unit.relPath()) != null
                    ? options.codepageOverrides().get(unit.relPath())
                    : options.codepageOverrides().get(unit.fileName());
            jp.cobolinsight.core.encoding.DecodedSource decoded = override == null
                    ? sourceDecoder.decode(bytes)
                    : sourceDecoder.decode(bytes, CodePage.fromName(override));
            byte[] fixedBytes = applier.apply(decoded, edits);
            Charset charset = decoded.encodingInfo().codePage().charset();
            boolean copybook = unit.kind() == AssetKind.COPYBOOK;
            List<String> importers = copybook
                    ? CopybookImporters.of(CopybookImporters.baseName(unit.relPath()),
                            programSourcesByRel)
                    : List.of();
            fileFixes.add(new FileFix(unit.relPath(),
                    decoded.encodingInfo().codePage().charsetName(),
                    decoded.text(), new String(fixedBytes, charset), fixedBytes,
                    new ArrayList<>(descriptionsBySource.get(entry.getKey())), copybook,
                    importers));
            fixCount += edits.size();
        }
        fileFixes.sort(Comparator.comparing(FileFix::relPath));

        return new Result(fileFixes, List.copyOf(s.findings()), fixCount);
    }
}
