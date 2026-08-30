import { useMemo, useRef, useState, type KeyboardEvent, type ReactElement } from "react";
import { text } from "../../i18n/text";
import { artifactItems, useProject } from "../../state/projectStore";
import { activeTabOf, useWorkbench } from "../../state/workbenchStore";
import {
  ASSET_TYPE_FILTERS,
  buildTreeRows,
  countFindingsByFile,
  toggleCollapsed,
  type AssetTypeCode,
  type AssetTypeFilter,
  type TreeRow,
} from "../../model/assetTree";
import { codepageLabel } from "../../../../shared/codepage";
import { ImportDialog } from "./ImportDialog";

export interface ExplorerProps {
  onSelectFolder: () => void;
  onOpenAsset: (path: string, line: number | null) => void;
}

/** The kind filter's label. */
function filterLabel(filter: AssetTypeFilter): string {
  return text.assetType[filter];
}

/** The codicon glyph shown before an asset kind badge's text. */
function badgeIcon(type: AssetTypeCode): string {
  switch (type) {
    case "cobol":
      return "symbol-method";
    case "copybook":
      return "symbol-snippet";
    case "jcl":
      return "list-ordered";
    case "bms":
      return "layout";
    case "other":
      return "file";
  }
}

/**
 * The asset explorer: the tree of scanned assets, with a name filter and a kind filter.
 *
 * The tree is a real ARIA tree: rows carry their level and their expanded state, one row holds the
 * tab stop, and the arrow keys move between rows and open and close folders. The five states —
 * empty, loading, results, no match and error — are all reachable and each says which it is.
 */
export function Explorer({ onSelectFolder, onOpenAsset }: ExplorerProps): ReactElement {
  const project = useProject();
  const workbench = useWorkbench();
  const activeTabPath = activeTabOf(workbench)?.path ?? null;
  const [search, setSearch] = useState("");
  const [typeFilter, setTypeFilter] = useState<AssetTypeFilter>("all");
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [focusedPath, setFocusedPath] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);
  const treeRef = useRef<HTMLDivElement | null>(null);

  const findingCounts = useMemo(
    () =>
      countFindingsByFile(
        artifactItems(project.findings),
        artifactItems(project.sqlFindings),
      ),
    [project.findings, project.sqlFindings],
  );

  const rows = useMemo(
    () =>
      buildTreeRows(artifactItems(project.inventory), {
        search,
        type: typeFilter,
        collapsed,
        findingCounts,
      }),
    [project.inventory, search, typeFilter, collapsed, findingCounts],
  );

  const activate = (row: TreeRow): void => {
    if (row.kind === "folder") {
      setCollapsed(toggleCollapsed(collapsed, row.path));
    } else {
      onOpenAsset(row.path, null);
    }
  };

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>): void => {
    const index = rows.findIndex((row) => row.path === focusedPath);
    const current = index < 0 ? 0 : index;
    const row = rows[current];
    if (row === undefined) {
      return;
    }
    const move = (to: number): void => {
      event.preventDefault();
      const target = rows[Math.min(Math.max(to, 0), rows.length - 1)];
      setFocusedPath(target.path);
      queueMicrotask(() =>
        treeRef.current
          ?.querySelector<HTMLElement>(`[data-testid="tree-${CSS.escape(target.path)}"]`)
          ?.focus(),
      );
    };
    switch (event.key) {
      case "ArrowDown":
        move(current + 1);
        break;
      case "ArrowUp":
        move(current - 1);
        break;
      case "Home":
        move(0);
        break;
      case "End":
        move(rows.length - 1);
        break;
      case "ArrowRight":
        if (row.kind === "folder" && !row.expanded) {
          event.preventDefault();
          setCollapsed(toggleCollapsed(collapsed, row.path));
        }
        break;
      case "ArrowLeft":
        if (row.kind === "folder" && row.expanded) {
          event.preventDefault();
          setCollapsed(toggleCollapsed(collapsed, row.path));
        }
        break;
      case "Enter":
      case " ":
        event.preventDefault();
        activate(row);
        break;
      default:
        break;
    }
  };

  const body = ((): ReactElement | null => {
    if (project.mode === "running" && project.inventory.status === "none") {
      return <p className="ci-explorer__state">{text.explorer.loading}</p>;
    }
    if (project.inventory.status === "error") {
      return (
        <p className="ci-explorer__state ci-explorer__state--error" role="alert">
          {text.explorer.error}
          <span className="ci-explorer__reason">{project.inventory.message}</span>
        </p>
      );
    }
    if (project.inventory.status === "none") {
      return null;
    }
    if (rows.length === 0) {
      return (
        <p className="ci-explorer__state" data-testid="tree-no-match">
          {text.explorer.noMatch}
        </p>
      );
    }
    const tabStop = focusedPath ?? rows[0].path;
    return (
      <div
        ref={treeRef}
        role="tree"
        aria-label={text.explorer.tree}
        className="ci-tree"
        onKeyDown={onKeyDown}
        data-testid="asset-tree"
      >
        {rows.map((row) => (
          <div
            key={`${row.kind}:${row.path}`}
            role="treeitem"
            aria-level={row.depth + 1}
            aria-expanded={row.kind === "folder" ? row.expanded : undefined}
            aria-selected={row.path === focusedPath || row.path === activeTabPath}
            tabIndex={row.path === tabStop ? 0 : -1}
            className={`ci-tree__row ci-tree__row--${row.kind}`}
            style={{ paddingInlineStart: `${row.depth * 12 + 8}px` }}
            onClick={() => {
              setFocusedPath(row.path);
              activate(row);
            }}
            onFocus={() => setFocusedPath(row.path)}
            data-testid={`tree-${row.path}`}
          >
            <span
              className={`codicon ${
                row.kind === "folder"
                  ? row.expanded
                    ? "codicon-chevron-down"
                    : "codicon-chevron-right"
                  : "codicon-file"
              }`}
              aria-hidden="true"
            />
            <span className="ci-tree__name">{row.name}</span>
            {row.type !== null ? (
              <span className={`ci-badge ci-badge--${row.type}`}>
                <span className={`codicon codicon-${badgeIcon(row.type)}`} aria-hidden="true" />
                {text.assetType[row.type]}
              </span>
            ) : null}
            {row.item !== null && row.item.codepage === null ? (
              <span className="ci-badge ci-badge--warn">{text.explorer.codepageUnknown}</span>
            ) : null}
            {row.item !== null && row.item.codepage !== null ? (
              <span className="ci-tree__codepage">{codepageLabel(row.item.codepage, "")}</span>
            ) : null}
            {row.findingCount > 0 ? (
              <span
                className="ci-badge ci-badge--count"
                aria-label={text.explorer.findingCount(row.findingCount)}
              >
                {row.findingCount}
              </span>
            ) : null}
          </div>
        ))}
      </div>
    );
  })();

  return (
    <div className="ci-explorer">
      <div className="ci-explorer__toolbar">
        <button
          type="button"
          className="ci-button"
          onClick={onSelectFolder}
          data-testid="explorer-select-folder"
        >
          {project.inputDir === null ? text.explorer.selectFolder : text.explorer.changeFolder}
        </button>
        <button
          type="button"
          className="ci-button"
          disabled={project.inputDir === null}
          title={project.inputDir === null ? text.import.noFolder : undefined}
          onClick={() => setImporting(true)}
          data-testid="explorer-import"
        >
          {text.import.open}
        </button>
      </div>
      <div className="ci-explorer__filters">
        <input
          type="search"
          className="ci-input"
          placeholder={text.explorer.search}
          aria-label={text.explorer.searchLabel}
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          data-testid="explorer-search"
        />
        <select
          className="ci-select"
          aria-label={text.explorer.typeLabel}
          value={typeFilter}
          onChange={(event) => setTypeFilter(event.target.value as AssetTypeFilter)}
          data-testid="explorer-type-filter"
        >
          {ASSET_TYPE_FILTERS.map((filter) => (
            <option key={filter} value={filter}>
              {filterLabel(filter)}
            </option>
          ))}
        </select>
      </div>
      {body}
      {importing && project.inputDir !== null ? (
        <ImportDialog inputDir={project.inputDir} onClose={() => setImporting(false)} />
      ) : null}
    </div>
  );
}
