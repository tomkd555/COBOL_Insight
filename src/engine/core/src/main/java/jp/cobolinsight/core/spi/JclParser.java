package jp.cobolinsight.core.spi;

import jp.cobolinsight.core.jcl.JclJobModel;
import jp.cobolinsight.core.source.DecodedSource;

import java.nio.file.Path;
import java.util.List;

/**
 * JCL解析の契約。カタログ化PROC展開とシンボリックパラメータ解決を済ませた
 * ジョブ構造モデルを返す。
 */
public interface JclParser {

    ParseOutcome<JclJobModel> parse(DecodedSource source, List<Path> procedureLibraryPaths);
}
