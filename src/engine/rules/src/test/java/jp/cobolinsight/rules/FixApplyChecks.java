package jp.cobolinsight.rules;

import jp.cobolinsight.core.finding.TextEdit;
import jp.cobolinsight.core.fix.ByteSpliceApplier;
import jp.cobolinsight.core.fix.ReparseResult;
import jp.cobolinsight.core.fix.ReparseVerifier;
import jp.cobolinsight.core.encoding.EncodingCharsetProvider;
import jp.cobolinsight.frontend.cobol.Che4zCobolParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Test helper that actually byte-splices the edits returned by a FixProducer onto the
 * original file and verifies that the fixed source can be re-parsed. An edit that causes
 * column misalignment, token merging, or literal corruption fails re-parsing, so this
 * backs up the unit-level checks on position and replacement text by confirming that the
 * generated fix is a well-formed fixed-format source.
 */
public final class FixApplyChecks {

    private FixApplyChecks() {
    }

    /** Applies a set of edits to the original file, decodes as UTF-8, and returns the re-parse result. */
    public static ReparseResult applyAndReparse(String originalFile, List<TextEdit> edits,
            List<Path> copybookSearchPaths) {
        byte[] fixed;
        try {
            fixed = new ByteSpliceApplier().apply(Path.of(originalFile), edits);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new ReparseVerifier(new Che4zCobolParser(), new EncodingCharsetProvider())
                .verify(originalFile, fixed, "UTF-8", copybookSearchPaths);
    }
}
