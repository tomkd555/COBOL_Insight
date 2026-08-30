package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.semantic.EmbeddedBlock;
import jp.cobolinsight.core.semantic.EmbeddedBlockKind;
import jp.cobolinsight.core.sql.SqlStatementModel;

/**
 * The contract for embedded SQL analysis. The input is a {@link EmbeddedBlockKind#SQL} block
 * extracted from the normalized semantic model; the implementation performs the preprocessing,
 * including reversible host variable mangling.
 */
public interface SqlParser {

    ParseOutcome<SqlStatementModel> parse(EmbeddedBlock sqlBlock);
}
