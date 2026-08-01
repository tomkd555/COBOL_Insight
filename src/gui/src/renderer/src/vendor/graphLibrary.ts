/**
 * 呼出関係図の描画ライブラリ。cytoscape へ ELK レイアウト(cytoscape-elk + elkjs)を1度だけ登録して返す。
 * どちらもローカル同梱物であり、CDN・外部ホストへは接続しない。図の全体一括描画は CLI の
 * callgraph --svg/--png へ寄せ、GUI 側はフィルタ・部分展開・詳細度制御を担う(裁定 A3)。
 */

import cytoscape from "cytoscape";
import elk from "cytoscape-elk";

let registered = false;

/** ELK レイアウト登録済みの cytoscape を返す。2度目以降は登録を繰り返さない。 */
export function graphLibrary(): typeof cytoscape {
  if (!registered) {
    cytoscape.use(elk);
    registered = true;
  }
  return cytoscape;
}
