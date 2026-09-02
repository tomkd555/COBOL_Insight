import { useEffect, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../../i18n/text";
import type { TraceNode, TraceRow } from "../../model/traceTree";

export interface TraceTreeProps {
  rows: readonly TraceRow[];
  /** The selected row's key, or null. */
  selectedId: string | null;
  /** Selecting a row also centres the canvas on the node it stands for. */
  onSelect: (node: TraceNode) => void;
  onToggle: (id: string) => void;
  /** Opens the asset at the row's line. Not called for a row that names no asset. */
  onOpen: (path: string, line: number | null) => void;
}

/** Where the arrow keys, Home and End move the focus, or null when the key moves nothing. */
function nextIndex(key: string, index: number, count: number): number | null {
  if (count === 0) {
    return null;
  }
  if (key === "ArrowDown") {
    return Math.min(index + 1, count - 1);
  }
  if (key === "ArrowUp") {
    return Math.max(index - 1, 0);
  }
  if (key === "Home") {
    return 0;
  }
  if (key === "End") {
    return count - 1;
  }
  return null;
}

/**
 * The execution-order tree: job, step, program and paragraph in the order the engine recorded them
 * running.
 *
 * The focus sits on one row at a time (roving tabindex): the up and down arrows move between rows,
 * the left and right arrows close and open them, and Enter selects the row and opens its asset. The
 * canvas cannot be reached with the keyboard, so this list is the equivalent route into the graph.
 */
export function TraceTree({
  rows,
  selectedId,
  onSelect,
  onToggle,
  onOpen,
}: TraceTreeProps): ReactElement {
  const treeRef = useRef<HTMLDivElement>(null);
  const [focusIndex, setFocusIndex] = useState(0);

  // A change of rows can leave the previous focus position past the end of the list.
  useEffect(() => {
    setFocusIndex((current) => (current < rows.length ? current : 0));
  }, [rows.length]);

  function focusRow(index: number): void {
    treeRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]')[index]?.focus();
  }

  function activate(row: TraceRow): void {
    onSelect(row.node);
    if (row.node.path !== null) {
      onOpen(row.node.path, row.node.line);
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>, index: number, row: TraceRow): void {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      setFocusIndex(index);
      activate(row);
      return;
    }
    // Right on an open row and left on a closed one change nothing, so they are left alone.
    if (row.hasChildren && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      if (row.expanded === (event.key === "ArrowLeft")) {
        event.preventDefault();
        onToggle(row.node.id);
        return;
      }
    }
    const next = nextIndex(event.key, index, rows.length);
    if (next === null) {
      return;
    }
    event.preventDefault();
    setFocusIndex(next);
    focusRow(next);
  }

  const rootCount = rows.filter((row) => row.depth === 0).length;

  return (
    <div className="ci-trace">
      <p className="ci-trace__title" id="ci-trace-title">
        {text.graph.traceRoots(rootCount)}
      </p>
      <div
        ref={treeRef}
        className="ci-trace__tree"
        role="tree"
        aria-labelledby="ci-trace-title"
        data-testid="trace-tree"
      >
        {rows.map((row, index) => {
          const selected = row.node.id === selectedId;
          const classes = ["ci-trace__row", `ci-trace__row--${row.node.kind}`];
          if (selected) {
            classes.push("ci-trace__row--selected");
          }
          return (
            <div
              key={row.node.id}
              role="treeitem"
              aria-level={row.depth + 1}
              aria-selected={selected}
              aria-expanded={row.hasChildren ? row.expanded : undefined}
              tabIndex={index === focusIndex ? 0 : -1}
              className={classes.join(" ")}
              style={{
                paddingInlineStart: `calc(var(--ci-space-2) + ${row.depth} * var(--ci-space-4))`,
              }}
              data-testid={`trace-${row.node.id}`}
              onClick={() => {
                setFocusIndex(index);
                activate(row);
              }}
              onKeyDown={(event) => onKeyDown(event, index, row)}
            >
              <button
                type="button"
                className="ci-trace__marker"
                tabIndex={-1}
                aria-label={text.graph.expand(row.node.label, row.expanded)}
                aria-hidden={row.hasChildren ? undefined : true}
                disabled={!row.hasChildren}
                onClick={(event) => {
                  event.stopPropagation();
                  onToggle(row.node.id);
                }}
              >
                {row.hasChildren ? (row.expanded ? "▾" : "▸") : ""}
              </button>
              <span className="ci-trace__kind">{text.graph.traceKind[row.node.kind]}</span>
              <span className="ci-trace__label">{row.node.label}</span>
              {row.node.cyclic ? <span className="ci-trace__note">{text.graph.cyclic}</span> : null}
              {row.node.line === null ? null : (
                <span className="ci-trace__line">{`:${row.node.line}`}</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
