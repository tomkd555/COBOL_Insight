package jp.cobolinsight.jclfrontend;

/**
 * DD 文。datasetName はシンボリック解決済みの DSN(DSN 指定が無い DD では null)。
 * line は PROC 展開・シンボリック解決後の作業ファイル上の行番号。
 */
public record ParsedDd(String ddName, String datasetName, int line) {
}
