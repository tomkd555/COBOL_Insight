import { useEffect, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
import { nextRovingIndex } from "../components/rovingList";
import { codepageLabel } from "../data/encodings";
import { ASSET_TYPE_LABELS, type TreeRow } from "./assetTreeModel";

export interface AssetTreeProps {
  rows: readonly TreeRow[];
  /** 選択中の資産の相対パス。未選択は空文字。 */
  selectedPath: string;
  onOpenFile: (path: string) => void;
  onToggleFolder: (path: string) => void;
}

/**
 * 資産ツリー。走査した相対パスの階層をそのまま並べる。
 *
 * 焦点は選択中の1行へ集約し(roving tabindex)、上下矢印で行を移り、Enter・Space で
 * 資産を開く・フォルダを開閉する。左右矢印はフォルダの開閉に当てる。
 */
export function AssetTree({
  rows,
  selectedPath,
  onOpenFile,
  onToggleFolder,
}: AssetTreeProps): ReactElement {
  const listRef = useRef<HTMLDivElement>(null);
  const [focusIndex, setFocusIndex] = useState(0);

  // 行が入れ替わると、前の焦点位置が並びの外を指すことがある。
  useEffect(() => {
    setFocusIndex((current) => (current < rows.length ? current : 0));
  }, [rows.length]);

  function focusRow(index: number): void {
    const items = listRef.current?.querySelectorAll<HTMLElement>('[role="treeitem"]');
    items?.[index]?.focus();
  }

  function activate(row: TreeRow): void {
    if (row.kind === "folder") {
      onToggleFolder(row.path);
    } else {
      onOpenFile(row.path);
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>, index: number, row: TreeRow): void {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      activate(row);
      return;
    }
    if (row.kind === "folder" && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      // 開いている状態で右、畳んでいる状態で左を押しても意味が無いため、状態が変わるときだけ効かせる。
      if (row.expanded === (event.key === "ArrowLeft")) {
        event.preventDefault();
        onToggleFolder(row.path);
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
    <div ref={listRef} className="ci-tree" role="tree" aria-label="資産">
      {rows.map((row, index) => {
        const selected = row.kind === "file" && row.path === selectedPath;
        const classes = ["ci-tree__row", `ci-tree__row--${row.kind}`];
        if (selected) classes.push("ci-tree__row--selected");
        return (
          <div
            key={`${row.kind}:${row.path}`}
            role="treeitem"
            aria-level={row.depth + 1}
            aria-selected={row.kind === "file" ? selected : undefined}
            aria-expanded={row.kind === "folder" ? row.expanded : undefined}
            tabIndex={index === focusIndex ? 0 : -1}
            className={classes.join(" ")}
            style={{ paddingInlineStart: `calc(var(--ci-space-2) + ${row.depth} * var(--ci-space-4))` }}
            data-testid={`tree-${row.path}`}
            onClick={() => {
              setFocusIndex(index);
              activate(row);
            }}
            onKeyDown={(event) => onKeyDown(event, index, row)}
          >
            <span className="ci-tree__marker" aria-hidden="true">
              {row.kind === "folder" ? (row.expanded ? "▾" : "▸") : ""}
            </span>
            <span className="ci-tree__name">{row.name}</span>
            {row.type !== null ? (
              <span className={`ci-badge ci-badge--${row.type}`}>{ASSET_TYPE_LABELS[row.type]}</span>
            ) : null}
            {row.item !== null && row.item.codepage === null ? (
              <span className="ci-tree__note">
                {codepageLabel(null)}
              </span>
            ) : null}
            {row.findingCount > 0 ? (
              <span className="ci-tree__count" aria-label={`指摘 ${row.findingCount} 件`}>
                {row.findingCount}
              </span>
            ) : null}
          </div>
        );
      })}
    </div>
  );
}
