package jp.cobolinsight.engineapi.spi;

import jp.cobolinsight.engineapi.jcl.JclJobModel;
import jp.cobolinsight.engineapi.source.DecodedSource;

import java.nio.file.Path;
import java.util.List;

/**
 * JCL解析の契約。カタログ化PROC展開とシンボリックパラメータ解決を済ませた
 * ジョブ構造モデルを返す。
 */
public interface JclParser {

    ParseOutcome<JclJobModel> parse(DecodedSource source, List<Path> procedureLibraryPaths);
}
