/**
 * cytoscape-elk(ELK レイアウトアダプタ)は型定義を同梱しないため、cytoscape 拡張として宣言する。
 * 実体は elkjs/lib/elk.bundled.js を読み込む UMD モジュールであり、外部への通信は行わない。
 */
declare module "cytoscape-elk" {
  import type cytoscape from "cytoscape";
  const extension: cytoscape.Ext;
  export default extension;
}
