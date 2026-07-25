import type { ReactElement } from "react";
import type { GraphNodeKind } from "../../state/appState";
import { Button } from "../../components/Button";
import { FilterChip } from "../../components/FilterChip";
import { NODE_KIND_STYLES } from "./graphModel";

export interface GraphToolbarProps {
  /** ノード種別フィルタの ON/OFF。 */
  kinds: Record<GraphNodeKind, boolean>;
  /** 種別ごとのグラフ全体でのノード件数。 */
  counts: Record<GraphNodeKind, number>;
  onToggleKind: (kind: GraphNodeKind) => void;
  visibleCount: number;
  totalCount: number;
  /** callgraph を再実行してグラフを取り直す。 */
  onRebuild: () => void;
  /** engine の callgraph --svg で図を書き出す。 */
  onExportSvg: () => void;
  /** engine の callgraph --png で図を書き出す。 */
  onExportPng: () => void;
  /** 起動中は二重起動を防ぐため操作を止める。 */
  busy: boolean;
}

/**
 * 呼出関係図上部のツールバー。ノード種別フィルタのチップ(種別ごとの件数付き)、表示件数、
 * 再構築、SVG／PNG 出力を並べる。チップは複数選択なのでトグルボタン(aria-pressed)として表す。
 * 種別と図形・配色の対応は凡例(GraphLegend)が示す。書出は engine の callgraph サブコマンドが
 * 行い、renderer はファイルを書かない。
 */
export function GraphToolbar({
  kinds,
  counts,
  onToggleKind,
  visibleCount,
  totalCount,
  onRebuild,
  onExportSvg,
  onExportPng,
  busy,
}: GraphToolbarProps): ReactElement {
  return (
    <div className="ci-graph__toolbar">
      <span className="ci-graph__toolbar-label" id="ci-graph-kind-label">
        ノード種別:
      </span>
      <div className="ci-graph__chips" role="group" aria-labelledby="ci-graph-kind-label">
        {NODE_KIND_STYLES.map((style) => (
          <FilterChip
            key={style.kind}
            label={style.label}
            count={counts[style.kind]}
            active={kinds[style.kind]}
            onClick={() => onToggleKind(style.kind)}
          />
        ))}
      </div>
      <span className="ci-graph__spacer" />
      <span className="ci-graph__count">{`表示 ${visibleCount} / 全 ${totalCount} ノード`}</span>
      <Button onClick={onRebuild} disabled={busy}>
        再構築
      </Button>
      <Button onClick={onExportSvg} disabled={busy}>
        SVG 出力
      </Button>
      <Button onClick={onExportPng} disabled={busy}>
        PNG 出力
      </Button>
    </div>
  );
}
