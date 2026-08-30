import type { ReactElement } from "react";
import { text } from "../../text";
import type { GraphEdgeDetail, GraphNodeDetail } from "../../model/graphDetail";
import {
  edgeKindStyles,
  nodeKindStyles,
  isGraphNodeKind,
  type GraphNodeKind,
} from "../../model/graphLayout";
import type { ThemeName } from "../../vendor/monarch";

export interface GraphDetailPaneProps {
  /** The palette the legend swatches are painted in. */
  theme: ThemeName;
  /** The selected node, or null when nothing is selected. */
  detail: GraphNodeDetail | null;
  /** Opens the asset the node stands for. */
  onOpenAsset: (path: string, line: number | null) => void;
}

/** The kind's name, falling back to the raw value for a kind this build does not know. */
function kindLabel(kind: string): string {
  return isGraphNodeKind(kind) ? text.graph.nodeKind[kind as GraphNodeKind] : kind;
}

function edgeKindLabel(kind: string): string {
  return kind in text.graph.edgeKind
    ? text.graph.edgeKind[kind as keyof typeof text.graph.edgeKind]
    : kind;
}

function resolutionLabel(resolution: string | null): string {
  if (resolution === null) {
    return "";
  }
  return resolution in text.graph.resolution
    ? text.graph.resolution[resolution as keyof typeof text.graph.resolution]
    : resolution;
}

/** One direction's edges, with the execution order and the calling line the engine recorded. */
function EdgeTable({
  caption,
  edges,
}: {
  caption: string;
  edges: readonly GraphEdgeDetail[];
}): ReactElement {
  if (edges.length === 0) {
    return (
      <section className="ci-graph__edges">
        <h4 className="ci-graph__subtitle">{caption}</h4>
        <p className="ci-graph__state">{text.graph.noEdges}</p>
      </section>
    );
  }
  return (
    <section className="ci-graph__edges">
      <h4 className="ci-graph__subtitle">{caption}</h4>
      <table className="ci-graph__table">
        <thead>
          <tr>
            <th scope="col">{text.graph.columnSeq}</th>
            <th scope="col">{text.graph.columnPeer}</th>
            <th scope="col">{text.graph.columnKind}</th>
            <th scope="col">{text.graph.columnLine}</th>
          </tr>
        </thead>
        <tbody>
          {edges.map((edge, index) => (
            <tr key={`${edge.peerId}-${edge.kind}-${edge.seq}-${index}`}>
              <td>{edge.seq === 0 ? "" : edge.seq}</td>
              <td>{edge.peerLabel}</td>
              <td>
                {edgeKindLabel(edge.kind)}
                {edge.resolution === null ? null : (
                  <span className="ci-graph__resolution">{resolutionLabel(edge.resolution)}</span>
                )}
              </td>
              <td>{edge.line ?? ""}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** The legend: every node kind's shape and colour, and every edge kind's colour. */
function Legend({ theme }: { theme: ThemeName }): ReactElement {
  return (
    <section className="ci-graph__legend" data-testid="graph-legend">
      <h4 className="ci-graph__subtitle">{text.graph.legend}</h4>
      <ul className="ci-graph__legend-list">
        {nodeKindStyles(theme).map((style) => (
          <li key={style.kind}>
            <span
              className="ci-graph__swatch"
              style={{
                background: style.background,
                borderColor: style.border,
                borderStyle: style.borderStyle,
              }}
              aria-hidden="true"
            />
            {text.graph.nodeKind[style.kind]}
          </li>
        ))}
        {edgeKindStyles(theme).map((style) => (
          <li key={style.kind}>
            <span
              className="ci-graph__swatch ci-graph__swatch--edge"
              style={{ background: style.color }}
              aria-hidden="true"
            />
            {edgeKindLabel(style.kind)}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The right-hand pane: what is selected, the calls into and out of it, and the legend. */
export function GraphDetailPane({ detail, theme, onOpenAsset }: GraphDetailPaneProps): ReactElement {
  return (
    <aside className="ci-graph__detail" aria-label={text.graph.detail} data-testid="graph-detail">
      {detail === null ? (
        <p className="ci-graph__state">{text.graph.detailNone}</p>
      ) : (
        <>
          <h3 className="ci-graph__detail-title" data-testid="graph-detail-label">
            {detail.node.label}
            <span className="ci-badge">{kindLabel(detail.node.type)}</span>
          </h3>
          {detail.path === null ? null : (
            <button
              type="button"
              className="ci-button"
              onClick={() => onOpenAsset(detail.path as string, null)}
              data-testid="graph-open-source"
            >
              {text.graph.openSource}
            </button>
          )}
          <EdgeTable caption={text.graph.incoming} edges={detail.incoming} />
          <EdgeTable caption={text.graph.outgoing} edges={detail.outgoing} />
        </>
      )}
      <Legend theme={theme} />
    </aside>
  );
}
