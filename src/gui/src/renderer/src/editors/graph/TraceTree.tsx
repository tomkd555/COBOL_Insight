import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
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
  /**
   * The name of the tree where something else already shows it — a tab, in the source view's pane.
   * The tree then draws no title of its own and carries the name for a screen reader only.
   */
  label?: string;
  /** The test id of the tree and the prefix of its rows'. The call graph's tree is `trace`. */
  testId?: string;
  /**
   * Changes with every selection made outside the tree, the same row selected again included; the
   * selected row is then brought into view and becomes the Tab stop. A selection made in the tree
   * leaves it as it is.
   */
  selectionKey?: number;
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
  label,
  testId = "trace",
  selectionKey = 0,
}: TraceTreeProps): ReactElement {
  const titleId = useId();
  const treeRef = useRef<HTMLDivElement>(null);
  /**
   * The row the Tab key enters the tree at, held by its id so that a row opened or closed above it
   * leaves the stop on the same row. A row no longer listed hands the stop to the selected row, and
   * with none listed to the first.
   */
  const [focusId, setFocusId] = useState<string | null>(null);
  const held = rows.findIndex((row) => row.node.id === focusId);
  const focusIndex =
    held >= 0 ? held : Math.max(rows.findIndex((row) => row.node.id === selectedId), 0);

  /** Brings the selected row into view. False while the tree holds no such row. */
  const reveal = (): boolean => {
    const row = treeRef.current?.querySelector<HTMLElement>(".ci-trace__row--selected");
    if (row === null || row === undefined) {
      return false;
    }
    // jsdom has no scrollIntoView; the guard keeps the tests running.
    if (typeof row.scrollIntoView === "function") {
      row.scrollIntoView({ block: "nearest" });
    }
    return true;
  };
  const revealRef = useRef(reveal);
  revealRef.current = reveal;

  // A selection made elsewhere is brought into view once, when its row is there, and the Tab key then
  // enters the tree at that row, which stays the Tab stop while rows above it open or close. Rows
  // opened or closed afterwards leave the scroll where the reader put it. Each selection made
  // elsewhere does this anew, the same row selected again included.
  const revealedRef = useRef<string | null>(null);
  useEffect(() => {
    const selection = `${selectionKey}:${selectedId ?? ""}`;
    if (selectedId === null) {
      revealedRef.current = null;
    } else if (revealedRef.current !== selection && reveal()) {
      revealedRef.current = selection;
      setFocusId(selectedId);
    }
  });

  // The selected row stays in view when the tree is given less height.
  useEffect(() => {
    const tree = treeRef.current;
    if (tree === null || typeof ResizeObserver === "undefined") {
      return;
    }
    const resized = new ResizeObserver(() => revealRef.current());
    resized.observe(tree);
    return () => resized.disconnect();
  }, []);

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
      setFocusId(row.node.id);
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
    setFocusId(rows[next].node.id);
    focusRow(next);
  }

  return (
    <div className="ci-trace">
      {label === undefined ? (
        <p className="ci-trace__title" id={titleId}>
          {text.graph.traceTitle}
        </p>
      ) : null}
      <div
        ref={treeRef}
        className="ci-trace__tree"
        role="tree"
        aria-labelledby={label === undefined ? titleId : undefined}
        aria-label={label}
        data-testid={`${testId}-tree`}
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
              data-testid={`${testId}-${row.node.id}`}
              onClick={() => {
                setFocusId(row.node.id);
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
              <span className="ci-trace__label" title={row.node.label}>
                {row.node.label}
              </span>
              {row.node.cyclic ? <span className="ci-trace__note">{text.graph.cyclic}</span> : null}
              {row.node.line === null ? null : (
                <span className="ci-trace__line">{text.graph.line(row.node.line)}</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
