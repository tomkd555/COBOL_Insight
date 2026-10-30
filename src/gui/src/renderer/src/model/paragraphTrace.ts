/**
 * The trace of one program from one of its paragraphs: what runs from there, in execution order, as
 * a tree and as the graph of the same movement.
 *
 * The flows come from the engine's scan (PARAGRAPH and PARAGRAPH_EDGE through programFlow). A
 * PERFORM returns, so the range it names is walked paragraph by paragraph — to the THRU paragraph,
 * to the last paragraph of a section, or the one paragraph alone — and the trace then carries on
 * with what follows the PERFORM. GO TO does not return: the trace follows it and keeps the end of
 * the range it left, since control comes back to the PERFORM only if it reaches that end. A call of
 * another program is a leaf that can open that program.
 *
 * A paragraph already on the way down stops the descent, so a loop is drawn once.
 */

import type { FlowEdge, FlowParagraph, ProgramFlow } from "../../../shared/ipc";
import { TRACE_NODE_BUDGET, type TraceNode } from "./traceTree";

export interface FlowNodeElement {
  readonly group: "nodes";
  readonly data: { readonly id: string; readonly label: string; readonly kind: string };
}

export interface FlowEdgeElement {
  readonly group: "edges";
  readonly data: {
    readonly id: string;
    readonly source: string;
    readonly target: string;
    readonly kind: string;
    /** The position, in the order of the tree, of the first row that took this flow. */
    readonly label: string;
  };
}

export type FlowElement = FlowNodeElement | FlowEdgeElement;

export interface ParagraphTrace {
  /** The start paragraph, or nothing where the program has no paragraph. */
  readonly roots: readonly TraceNode[];
  readonly elements: readonly FlowElement[];
  /** Whether the row budget ran out before the trace was complete. */
  readonly truncated: boolean;
}

interface Build {
  readonly position: ReadonlyMap<number, number>;
  readonly paragraphs: readonly FlowParagraph[];
  readonly byId: ReadonlyMap<number, FlowParagraph>;
  readonly edgesFrom: ReadonlyMap<number, readonly FlowEdge[]>;
  readonly nodes: Map<string, FlowNodeElement>;
  readonly edges: Map<string, FlowEdgeElement>;
  left: number;
  order: number;
  truncated: boolean;
}

const EMPTY: ParagraphTrace = { roots: [], elements: [], truncated: false };

function nodeKindOf(paragraph: FlowParagraph): string {
  return paragraph.kind === "SECTION" ? "SECTION" : "PARAGRAPH";
}

/** The last paragraph of a section, or the paragraph itself: where a PERFORM of it returns from. */
function rangeEndOf(build: Build, paragraph: FlowParagraph): FlowParagraph {
  if (paragraph.kind !== "SECTION") {
    return paragraph;
  }
  let end = paragraph;
  const start = build.position.get(paragraph.id) ?? 0;
  for (let index = start + 1; index < build.paragraphs.length; index++) {
    const next = build.paragraphs[index] as FlowParagraph;
    if (next.kind === "SECTION" || next.sectionName?.toUpperCase() !== paragraph.name.toUpperCase()) {
      break;
    }
    end = next;
  }
  return end;
}

function addNode(build: Build, id: string, label: string, kind: string): void {
  if (!build.nodes.has(id)) {
    build.nodes.set(id, { group: "nodes", data: { id, label, kind } });
  }
}

/** Counts the row against the budget and records the flow it took, numbered by its place in the tree. */
function take(build: Build, source: string, target: string, kind: string): void {
  build.left -= 1;
  build.order += 1;
  const id = `${source}|${target}|${kind}`;
  if (!build.edges.has(id)) {
    build.edges.set(id, {
      group: "edges",
      data: { id, source, target, kind, label: String(build.order) },
    });
  }
}

/**
 * The rows under an entry into `entry`: its own flows, then each paragraph the flow falls into, up
 * to and including `end` (null: until the flow stops of itself).
 */
function walk(
  build: Build,
  entry: FlowParagraph,
  end: number | null,
  ancestors: ReadonlySet<number>,
  parentId: string,
): TraceNode[] {
  const onPath = new Set(ancestors);
  onPath.add(entry.id);
  const rows = ownRows(build, entry, end, onPath, parentId);
  let current = entry;
  while (current.id !== end && !build.truncated) {
    const fall = (build.edgesFrom.get(current.id) ?? []).find((edge) => edge.kind === "FALLTHROUGH");
    const next = fall === undefined || fall.to === null ? undefined : build.byId.get(fall.to);
    if (next === undefined) {
      break;
    }
    if (build.left <= 0) {
      build.truncated = true;
      break;
    }
    const id = `${parentId}/${rows.length}`;
    addNode(build, String(next.id), next.name, nodeKindOf(next));
    take(build, String(current.id), String(next.id), "FALLTHROUGH");
    const repeated = onPath.has(next.id);
    onPath.add(next.id);
    rows.push({
      id,
      kind: "fallthrough",
      label: next.name,
      line: next.startLine,
      path: null,
      graphId: String(next.id),
      cyclic: repeated,
      children: repeated ? [] : ownRows(build, next, end, onPath, id),
    });
    if (repeated) {
      break;
    }
    current = next;
  }
  return rows;
}

/** The PERFORM, GO TO and call rows of one paragraph. */
function ownRows(
  build: Build,
  paragraph: FlowParagraph,
  end: number | null,
  onPath: ReadonlySet<number>,
  parentId: string,
): TraceNode[] {
  const rows: TraceNode[] = [];
  for (const edge of build.edgesFrom.get(paragraph.id) ?? []) {
    if (edge.kind === "FALLTHROUGH") {
      continue;
    }
    if (build.left <= 0) {
      build.truncated = true;
      break;
    }
    rows.push(...edgeRows(build, paragraph, edge, end, onPath, `${parentId}/${rows.length}`));
  }
  return rows;
}

/** The row of one flow leaving `from`, with what runs under it. */
function edgeRows(
  build: Build,
  from: FlowParagraph,
  edge: FlowEdge,
  end: number | null,
  onPath: ReadonlySet<number>,
  id: string,
): TraceNode[] {
  const source = String(from.id);
  if (edge.kind === "CALL" || edge.kind === "XCTL") {
    const target = `program:${edge.toName.toUpperCase()}`;
    addNode(build, target, edge.toName, edge.toPath === null ? "UNRESOLVED" : "PROGRAM");
    take(build, source, target, edge.kind);
    return [
      {
        id,
        kind: edge.kind === "CALL" ? "call" : "xctl",
        label: edge.toName,
        line: edge.line,
        path: edge.toPath,
        graphId: target,
        children: [],
        cyclic: false,
      },
    ];
  }

  const target = edge.to === null ? undefined : build.byId.get(edge.to);
  if (target === undefined) {
    const missing = `unresolved:${edge.toName.toUpperCase()}`;
    addNode(build, missing, edge.toName, "UNRESOLVED");
    take(build, source, missing, edge.kind);
    return [
      {
        id,
        kind: "unresolved",
        label: edge.toName,
        line: edge.line,
        path: null,
        graphId: missing,
        children: [],
        cyclic: false,
      },
    ];
  }

  addNode(build, String(target.id), target.name, nodeKindOf(target));
  take(build, source, String(target.id), edge.kind);
  const repeated = onPath.has(target.id);

  if (edge.kind === "GOTO") {
    return [
      {
        id,
        kind: "goto",
        label: target.name,
        line: edge.line ?? target.startLine,
        path: null,
        graphId: String(target.id),
        cyclic: repeated,
        children: repeated ? [] : walk(build, target, end, onPath, id),
      },
    ];
  }

  // PERFORM: the range runs to the THRU paragraph where one is named and lies at or after the
  // target; a THRU name that cannot serve is shown, and the range is then the target's own.
  const thru = edge.thru === null ? undefined : build.byId.get(edge.thru);
  const thruUsable =
    thru !== undefined && (build.position.get(thru.id) ?? -1) >= (build.position.get(target.id) ?? 0);
  const rangeEnd = rangeEndOf(build, thruUsable ? (thru as FlowParagraph) : target);
  const label = thruUsable && thru !== undefined ? `${target.name} THRU ${thru.name}` : target.name;
  const children = repeated ? [] : walk(build, target, rangeEnd.id, onPath, id);
  const rows: TraceNode[] = [
    {
      id,
      kind: "perform",
      label,
      line: edge.line ?? target.startLine,
      path: null,
      graphId: String(target.id),
      cyclic: repeated,
      children,
    },
  ];
  if (edge.thruName !== null && !thruUsable) {
    build.left -= 1;
    rows.push({
      id: `${id}t`,
      kind: "unresolved",
      label: `THRU ${edge.thruName}`,
      line: edge.line,
      path: null,
      graphId: null,
      children: [],
      cyclic: false,
    });
  }
  return rows;
}

/**
 * Builds the trace from one paragraph. `startId` null starts at the first paragraph of the program,
 * which is where the procedure division is entered.
 */
export function buildParagraphTrace(flow: ProgramFlow, startId: number | null): ParagraphTrace {
  const start = startId === null ? flow.paragraphs[0] : flow.paragraphs.find((each) => each.id === startId);
  if (start === undefined) {
    return EMPTY;
  }
  const edgesFrom = new Map<number, FlowEdge[]>();
  for (const edge of flow.edges) {
    const list = edgesFrom.get(edge.from);
    if (list === undefined) {
      edgesFrom.set(edge.from, [edge]);
    } else {
      list.push(edge);
    }
  }
  for (const list of edgesFrom.values()) {
    list.sort((left, right) => left.seq - right.seq);
  }
  const build: Build = {
    position: new Map(flow.paragraphs.map((paragraph, index) => [paragraph.id, index])),
    paragraphs: flow.paragraphs,
    byId: new Map(flow.paragraphs.map((paragraph) => [paragraph.id, paragraph])),
    edgesFrom,
    nodes: new Map(),
    edges: new Map(),
    left: TRACE_NODE_BUDGET,
    order: 0,
    truncated: false,
  };
  addNode(build, String(start.id), start.name, nodeKindOf(start));
  const root: TraceNode = {
    id: "start",
    kind: start.kind === "SECTION" ? "section" : "paragraph",
    label: start.name,
    line: start.startLine,
    path: null,
    graphId: String(start.id),
    cyclic: false,
    children: walk(build, start, null, new Set(), "start"),
  };
  return {
    roots: [root],
    elements: [...build.nodes.values(), ...build.edges.values()],
    truncated: build.truncated,
  };
}

/** What a tree row has to carry to be found by the graph node it stands for. */
interface RowOfGraphNode {
  readonly id: string;
  readonly graphId: string | null;
  readonly children: readonly RowOfGraphNode[];
}

/**
 * The ids of the rows leading to the first row that stands for a graph node, that row last; empty
 * when no row does. Opening the rows before the last brings it into view.
 */
export function rowPathTo(roots: readonly RowOfGraphNode[], graphId: string): string[] {
  for (const node of roots) {
    if (node.graphId === graphId) {
      return [node.id];
    }
    const below = rowPathTo(node.children, graphId);
    if (below.length > 0) {
      return [node.id, ...below];
    }
  }
  return [];
}

/**
 * The engine's paragraph for a header the editor's outline holds: the one of that name in that
 * section, or outside every section where none is given. Null where the last scan holds none, so a
 * header the scan has yet to read is never answered with its namesake in another section.
 */
export function flowParagraphOf(
  flow: ProgramFlow,
  name: string,
  section: string | null,
): FlowParagraph | null {
  const wanted = name.toUpperCase();
  const within = section?.toUpperCase() ?? null;
  return (
    flow.paragraphs.find(
      (paragraph) =>
        paragraph.name.toUpperCase() === wanted &&
        (paragraph.sectionName?.toUpperCase() ?? null) === within,
    ) ?? null
  );
}
