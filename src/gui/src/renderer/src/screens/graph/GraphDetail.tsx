import type { ReactElement } from "react";
import { Button } from "../../components/Button";
import { GraphLegend } from "./GraphLegend";
import type { GraphEdgeDetail, GraphNodeDetail } from "./graphModel";

export interface GraphDetailProps {
  /** 選択中ノードの詳細。未選択は null。 */
  detail: GraphNodeDetail | null;
  /** 選択中ノードを展開済みか。 */
  expanded: boolean;
  onToggleExpanded: () => void;
  /** 対応するソースの相対パス。対応が無ければ空で、ソースを開く操作を出さない。 */
  sources: readonly string[];
  /** ソースを開く操作。開く資産の相対パスを渡す。 */
  onOpenSource: (path: string) => void;
}

/**
 * 右の詳細ペイン。選択ノードの種別・ID・表示名・属性を示した直後にソースを開く操作を置き(SQL指摘の
 * 詳細ペインでジャンプ操作を対象の識別情報のすぐ下に置くのと位置をそろえる)、続けて入出力エッジと
 * 隣接の展開・畳み込みを示す。下段に凡例を常時表示して、図の記号の意味を参照できるようにする。
 *
 * クリック・Enter/Space は選択だけを行う規則(指摘一覧・SQL指摘と共通)のもと、ソースを開く操作は
 * 常にこのボタンで行う。呼出関係はノードに行番号を持たないため、SQL指摘の「該当ソース行へ」とは
 * 揃えず「ソースを開く」とし、行を指定せず開くことを文言でも示す。
 *
 * 対応するソースが複数ある(同名のファイルが別フォルダにある)場合は、相対パス全体を添えて
 * すべてを操作として出す。先頭の候補を黙って開くと別フォルダの同名ファイルを取り違える。
 */
export function GraphDetail({
  detail,
  expanded,
  onToggleExpanded,
  sources,
  onOpenSource,
}: GraphDetailProps): ReactElement {
  return (
    <aside className="ci-graph-detail" aria-label="ノード情報と凡例">
      <h3 className="ci-graph-detail__title">ノード情報</h3>
      {detail === null ? (
        <p className="ci-graph-detail__empty">ノードを選んでください。</p>
      ) : (
        <div className="ci-graph-detail__body">
          <div>
            <span
              className="ci-graph-detail__kind"
              style={{
                background: detail.kindStyle.background,
                borderColor: detail.kindStyle.border,
                color: detail.kindStyle.border,
              }}
            >
              {detail.kindStyle.label}
            </span>
          </div>
          <p className="ci-graph-detail__name">{detail.node.label}</p>
          <dl className="ci-graph-detail__meta">
            <dt>ID</dt>
            <dd className="ci-graph-detail__id">{detail.node.id}</dd>
            {detail.attributes.map((attribute) => (
              <div key={attribute.key} className="ci-graph-detail__attribute">
                <dt>{attribute.key}</dt>
                <dd>{attribute.value}</dd>
              </div>
            ))}
          </dl>
          {sources.length === 0 ? null : (
            // SQL指摘の詳細ペインと同じく、ソースを開く操作はノードを特定する情報のすぐ下に置く。
            // 呼出関係はノードに行番号を持たないため、行指定なしで開くことを文言でも示す
            // (「該当ソース行へ」ではなく「ソースを開く」とする)。
            <div className="ci-graph-detail__actions">
              {sources.length === 1 ? (
                <Button variant="primary" onClick={() => onOpenSource(sources[0])}>
                  ソースを開く
                </Button>
              ) : (
                <>
                  <p className="ci-graph-detail__note">
                    同名の資産が複数あります。開く資産を相対パスで選んでください。
                  </p>
                  {sources.map((path) => (
                    <Button key={path} variant="primary" onClick={() => onOpenSource(path)}>
                      {`ソースを開く ― ${path}`}
                    </Button>
                  ))}
                </>
              )}
            </div>
          )}
          <EdgeList title="入ってくるエッジ" edges={detail.incoming} direction="←" />
          <EdgeList title="出ていくエッジ" edges={detail.outgoing} direction="→" />
          <div className="ci-graph-detail__actions">
            {detail.expandable ? (
              <Button onClick={onToggleExpanded}>
                {expanded ? "隣接を畳む" : `隣接を展開${detail.hiddenNeighborCount > 0 ? `（未表示 ${detail.hiddenNeighborCount}）` : ""}`}
              </Button>
            ) : (
              <p className="ci-graph-detail__note">このノードに隣接はありません。</p>
            )}
          </div>
        </div>
      )}
      <GraphLegend />
    </aside>
  );
}

interface EdgeListProps {
  title: string;
  edges: readonly GraphEdgeDetail[];
  /** 相手側ノードへの向きを示す記号。 */
  direction: string;
}

function EdgeList({ title, edges, direction }: EdgeListProps): ReactElement {
  return (
    <section className="ci-graph-detail__edges">
      <h3 className="ci-graph-detail__edges-title">{`${title}（${edges.length}）`}</h3>
      {edges.length === 0 ? null : (
        <ul>
          {edges.map((edge) => (
            <li key={`${direction}${edge.peerId}${edge.kindLabel}${edge.resolutionLabel}`}>
              <span className="ci-graph-detail__edge-peer">
                {direction} {edge.peerLabel}
              </span>
              <span className="ci-graph-detail__edge-kind">{edge.kindLabel}</span>
              <span
                className={
                  edge.dashed
                    ? "ci-graph-detail__edge-resolution ci-graph-detail__edge-resolution--dashed"
                    : "ci-graph-detail__edge-resolution"
                }
              >
                {edge.dashed ? `破線: ${edge.resolutionLabel}` : edge.resolutionLabel}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
