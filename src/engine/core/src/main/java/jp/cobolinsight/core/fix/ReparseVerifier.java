package jp.cobolinsight.core.fix;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;
import jp.cobolinsight.core.spi.CharsetProvider;
import jp.cobolinsight.core.spi.CobolParser;
import jp.cobolinsight.core.spi.ParseOutcome;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * A verification gate that reparses the fixed source with the COBOL parser and detects fixes
 * that cannot be parsed due to fixed-format column corruption, token merging, literal
 * corruption, and the like.
 *
 * <p>The parser and character decoder are passed in by the caller. core holds no compile
 * dependency on implementation modules; app's EngineWiring is the single place that chooses the
 * implementation.
 */
public final class ReparseVerifier {

    private final CobolParser parser;
    private final CharsetProvider charsetProvider;

    public ReparseVerifier(CobolParser parser, CharsetProvider charsetProvider) {
        this.parser = parser;
        this.charsetProvider = charsetProvider;
    }

    /** Reparses the decoded source. */
    public ReparseResult verify(DecodedSource fixedSource, List<Path> copybookSearchPaths) {
        ParseOutcome<CobolSemanticModel> outcome = parser.parse(fixedSource, copybookSearchPaths);
        return new ReparseResult(outcome.isSuccess(), outcome.failureFinding());
    }

    /**
     * Decodes the fixed byte sequence with the given code page and reparses it. The same code
     * page name as the original is passed in so that columns are counted using the same
     * character encoding as when the fix was applied.
     */
    public ReparseResult verify(String path, byte[] fixedBytes, String charsetName,
            List<Path> copybookSearchPaths) {
        return verify(charsetProvider.decode(path, fixedBytes, charsetName), copybookSearchPaths);
    }

    /** Reparses the decoded text. */
    public ReparseResult verify(String path, String fixedText, List<Path> copybookSearchPaths) {
        // The parser only reads the path and the text. The decoded text is encoded to UTF-8 and
        // decoded back to the same text, to obtain engine-api's DecodedSource.
        return verify(path, fixedText.getBytes(StandardCharsets.UTF_8), "UTF-8", copybookSearchPaths);
    }

}
