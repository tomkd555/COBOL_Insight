package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.source.DecodedSource;

import java.nio.file.Path;
import java.util.List;

/**
 * The contract for JCL analysis. Returns a job structure model with cataloged PROC expansion and
 * symbolic parameter resolution already applied.
 */
public interface JclParser {

    ParseOutcome<JclJobModel> parse(DecodedSource source, List<Path> procedureLibraryPaths);
}
