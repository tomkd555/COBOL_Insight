import { useEffect, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
import { nextRovingIndex } from "../components/rovingList";
import { TRACE_KIND_LABELS, type TraceNode, type TraceRow } from "./traceModel";

export interface TraceTreeProps {
  rows: readonly TraceRow[];
  /** 選んでいる節の鍵。未選択は空文字。 */
  selectedId: string;
  /** 節を選ぶ。図の強調もこの通知から行う。 */
  onSelect: (node: TraceNode) => void;
  /** 節の開閉を切り替える。 */
  onToggle: (id: string) => void;
  /** 資産の該当行を開く。開く先を持たない節では呼ばない。 */
  onOpen: (path: string, line: number | null) => void;
}

/**
 * 実行順の一覧。ジョブ・ステップ・プログラム・段落を、engine が記録した実行の順で入れ子に並べる。
 *
 * 焦点は1行へ集約し(roving tabindex)、上下矢印で行を移り、左右矢印で開閉する。Enter・Space は
 * その節を選んだうえで、開く先を持つ節なら資産の該当行を開く(図の canvas はキーボードで辿れない
 * ため、この一覧が図と等価な操作口を兼ねる)。
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

  // 行が入れ替わると、前の焦点位置が並びの外を指すことがある。
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
    // 開いている状態で右、畳んでいる状態で左を押しても状態は変わらないため、変わるときだけ効かせる。
    if (row.hasChildren && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      if (row.expanded === (event.key === "ArrowLeft")) {
        event.preventDefault();
        onToggle(row.node.id);
        return;
      }
    }
    const next = nextRovingIndex(event.key, index, rows.length);
    if (next === null) {
      return;
    }
    event.preventDefault();
    setFocusIndex(next);
    focusRow(next);
  }

  return (
    <div className="ci-trace">
      <p className="ci-trace__title" id="ci-trace-title">
        {`実行順（起点 ${rows.filter((row) => row.depth === 0).length}）`}
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
          if (selected) classes.push("ci-trace__row--selected");
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
                aria-label={`${row.node.label} の下位を${row.expanded ? "畳む" : "開く"}`}
                aria-hidden={row.hasChildren ? undefined : true}
                disabled={!row.hasChildren}
                onClick={(event) => {
                  event.stopPropagation();
                  onToggle(row.node.id);
                }}
              >
                {row.hasChildren ? (row.expanded ? "▾" : "▸") : ""}
              </button>
              <span className="ci-trace__kind">{TRACE_KIND_LABELS[row.node.kind]}</span>
              <span className="ci-trace__label">{row.node.label}</span>
              {row.node.cyclic ? (
                <span className="ci-trace__note">巡回のためここで止めています</span>
              ) : null}
              {row.node.line === null ? null : (
                <span className="ci-trace__line" aria-label={`${row.node.line} 行目`}>
                  {`:${row.node.line}`}
                </span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
