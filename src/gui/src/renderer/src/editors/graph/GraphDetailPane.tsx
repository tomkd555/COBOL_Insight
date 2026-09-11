import type { ReactElement } from "react";
import { text } from "../../i18n/text";
import type { GraphEdgeDetail, GraphNodeDetail } from "../../model/graphDetail";
import {
  edgeKindStyles,
  isGraphNodeKind,
  type GraphNodeKind,
  type NodeKindStyle,
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

/** The inner mark for one node kind's swatch, in a 16x16 viewBox, matching the cytoscape shape. */
function shapeMark(shape: NodeKindStyle["shape"]): ReactElement {
  switch (shape) {
    case "hexagon":
      return <polygon points="4,1 12,1 15,8 12,15 4,15 1,8" />;
    case "round-rectangle":
      return <rect x="1" y="3" width="14" height="10" rx="3" />;
    case "rectangle":
      return <rect x="1" y="3" width="14" height="10" />;
    case "ellipse":
      return <circle cx="8" cy="8" r="6.5" />;
    case "barrel":
      return <rect x="1" y="3" width="14" height="10" rx="5" ry="3" />;
    case "cut-rectangle":
      return <polygon points="3,1 13,1 15,3 15,13 13,15 3,15 1,13 1,3" />;
    case "octagon":
      return <polygon points="5,1 11,1 15,5 15,11 11,15 5,15 1,11 1,5" />;
    case "rhomboid":
      return <polygon points="5,2 15,2 11,14 1,14" />;
    case "tag":
      return <polygon points="1,2 11,2 15,8 11,14 1,14" />;
    case "diamond":
      return <polygon points="8,1 15,8 8,15 1,8" />;
    case "star":
      return (
        <polygon points="8,1 9.9,5.9 15,6.2 11,9.6 12.3,14.6 8,11.8 3.7,14.6 5,9.6 1,6.2 6.1,5.9" />
      );
    default:
      return <rect x="1" y="3" width="14" height="10" />;
  }
}

/** One node kind's swatch: the cytoscape shape it is drawn with, not a generic square. */
export function NodeSwatch({ style }: { style: NodeKindStyle }): ReactElement {
  return (
    <svg
      className="ci-graph__swatch"
      viewBox="0 0 16 16"
      width="14"
      height="14"
      aria-hidden="true"
      fill={style.background}
      stroke={style.border}
      strokeWidth={1.5}
      strokeDasharray={style.borderStyle === "dashed" ? "2 1.5" : undefined}
    >
      {shapeMark(style.shape)}
    </svg>
  );
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
            <th scope="col">{text.graph.columnPeer}</th>
            <th scope="col">{text.graph.columnSeq}</th>
            <th scope="col">{text.graph.columnKind}</th>
            <th scope="col">{text.graph.columnLine}</th>
          </tr>
        </thead>
        <tbody>
          {edges.map((edge, index) => (
            <tr key={`${edge.peerId}-${edge.kind}-${edge.seq}-${index}`}>
              <td>{edge.peerLabel}</td>
              <td>{edge.seq === 0 ? "" : edge.seq}</td>
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

/** The legend: every edge kind's colour. Node kinds show their swatch on their own toolbar chip. */
function Legend({ theme, open }: { theme: ThemeName; open: boolean }): ReactElement {
  return (
    <details key={String(open)} className="ci-graph__legend" open={open} data-testid="graph-legend">
      <summary className="ci-graph__subtitle">{text.graph.legend}</summary>
      <ul className="ci-graph__legend-list">
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
    </details>
  );
}

/** The right-hand pane: what is selected, the calls into and out of it, and the legend. */
export function GraphDetailPane({
  detail,
  theme,
  onOpenAsset,
}: GraphDetailPaneProps): ReactElement {
  return (
    <aside
      className={`ci-graph__detail${detail === null ? " ci-graph__detail--unselected" : ""}`}
      aria-label={text.graph.detail}
      data-testid="graph-detail"
    >
      {detail === null ? null : (
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
      <Legend theme={theme} open={detail === null} />
    </aside>
  );
}
