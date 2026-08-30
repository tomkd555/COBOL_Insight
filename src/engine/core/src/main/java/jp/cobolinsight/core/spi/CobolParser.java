package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.semantic.CobolSemanticModel;
import jp.cobolinsight.core.source.DecodedSource;

import java.nio.file.Path;
import java.util.List;

/**
 * The contract for COBOL syntax and semantic analysis. Builds the normalized semantic model,
 * including COPY/REPLACE expansion and EXEC SQL / EXEC CICS extraction. When multiple copybooks
 * share a name, resolution takes the first match in search-path order.
 */
public interface CobolParser {

    ParseOutcome<CobolSemanticModel> parse(DecodedSource source, List<Path> copybookSearchPaths);
}
