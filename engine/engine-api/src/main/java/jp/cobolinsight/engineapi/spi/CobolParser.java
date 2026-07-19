package jp.cobolinsight.engineapi.spi;

import jp.cobolinsight.engineapi.semantic.CobolSemanticModel;
import jp.cobolinsight.engineapi.source.DecodedSource;

import java.nio.file.Path;
import java.util.List;

/**
 * COBOL構文・意味解析の契約。COPY/REPLACE展開と EXEC SQL / EXEC CICS 抽出を含めて
 * 正規化意味モデルを構築する。コピー句の同名解決は探索パス順の先勝ちとする。
 */
public interface CobolParser {

    ParseOutcome<CobolSemanticModel> parse(DecodedSource source, List<Path> copybookSearchPaths);
}
