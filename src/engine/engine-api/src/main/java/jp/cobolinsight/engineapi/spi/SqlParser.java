package jp.cobolinsight.engineapi.spi;

import jp.cobolinsight.engineapi.semantic.EmbeddedBlock;
import jp.cobolinsight.engineapi.semantic.EmbeddedBlockKind;
import jp.cobolinsight.engineapi.sql.SqlStatementModel;

/**
 * 埋め込みSQL解析の契約。入力は正規化意味モデルから抽出した {@link EmbeddedBlockKind#SQL} の
 * ブロックで、ホスト変数の可逆マングリングを含む前処理は実装側が行う。
 */
public interface SqlParser {

    ParseOutcome<SqlStatementModel> parse(EmbeddedBlock sqlBlock);
}
