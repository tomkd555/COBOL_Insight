package jp.cobolinsight.app.pipeline;

import jp.cobolinsight.app.EngineWiring;
import jp.cobolinsight.core.finding.Finding;
import jp.cobolinsight.core.finding.FindingLevel;
import jp.cobolinsight.core.source.AssetKind;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.source.SourcePosition;
import jp.cobolinsight.core.spi.CharsetProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/**
 * Reads and decodes every unit. A file that cannot be read is dropped from the run and reported;
 * one file must not stop the analysis of the rest.
 *
 * <p>{@code reportFailuresFor} names the kinds whose decode failure becomes a finding. {@code fix}
 * and {@code translate} pull copybooks in from search paths that may hold unrelated files, and a
 * copybook they cannot decode is not a defect in the assets under analysis.
 */
public record Decode(Set<AssetKind> reportFailuresFor) implements Step {

    /** Rule ID for a source whose bytes could not be decoded with any candidate code page. */
    public static final String DECODE_FAILURE_RULE_ID = "decode-failure";

    @Override
    public void apply(SourceSet s) {
        CharsetProvider charsets = EngineWiring.charsetProvider();
        for (SourceUnit unit : List.copyOf(s.units())) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(unit.absPath());
            } catch (IOException e) {
                System.err.println("警告: " + unit.absPath() + " を読み込めませんでした（"
                        + Failures.describe(e) + "）。この資産は解析の対象外です。");
                s.unreadable().add(unit.relPath());
                continue;
            }
            s.bytes().put(unit.relPath(), bytes);
            try {
                s.decoded().put(unit.relPath(), decode(charsets, s, unit, bytes));
            } catch (IllegalArgumentException e) {
                if (unit.kind() != AssetKind.COPYBOOK) {
                    // A copybook has no node of its own in the call graph; it is reached through
                    // the programs that copy it, and those report their own failure to parse.
                    s.unanalyzable().put(unit.relPath(), "復号に失敗しました: " + e.getMessage());
                }
                if (reportFailuresFor.contains(unit.kind())) {
                    s.addFinding(unit.relPath(), Finding.of(DECODE_FAILURE_RULE_ID,
                            FindingLevel.ERROR, "復号に失敗しました: " + e.getMessage(),
                            SourcePosition.fileStart(unit.relPath())));
                }
            }
        }
        s.units().removeIf(unit -> s.unreadable().contains(unit.relPath()));
    }

    /** The manual code page for a unit: by relative path first, then by file name. */
    static String overrideOf(SourceSet s, SourceUnit unit) {
        String override = s.options().codepageOverrides().get(unit.relPath());
        return override != null ? override : s.options().codepageOverrides().get(unit.fileName());
    }

    static DecodedSource decode(CharsetProvider charsets, SourceSet s, SourceUnit unit,
            byte[] bytes) {
        String override = overrideOf(s, unit);
        String path = unit.absPath().toString();
        return override == null ? charsets.decode(path, bytes)
                : charsets.decode(path, bytes, override);
    }
}
