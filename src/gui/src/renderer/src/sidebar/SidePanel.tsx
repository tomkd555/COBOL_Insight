import { useMemo, useState, type ReactElement } from "react";
import { Button } from "../components/Button";
import { ChipRadioGroup } from "../components/ChipRadioGroup";
import { TextInput } from "../components/TextInput";
import { EmptyState } from "../components/EmptyState";
import { RunningIndicator, RUN_STAGES } from "../components/RunningIndicator";
import { artifactItems, useProject } from "../state/projectStore";
import { sourceTab, useWorkbenchDispatch } from "../state/workbenchStore";
import { scanNotices } from "../services/scanSummary";
import { AssetTree } from "./AssetTree";
import {
  ASSET_TYPE_FILTERS,
  assetTypeFilterLabel,
  buildTreeRows,
  countFindingsByFile,
  toggleCollapsed,
  type AssetTypeFilter,
} from "./assetTreeModel";

export interface SidePanelProps {
  /** 資産フォルダを選び直す。 */
  onSelectFolder: () => void;
  /** 選んである資産フォルダを解析し直す。 */
  onReanalyze: () => void;
  /** 端末取込のダイアログを開く。 */
  onImport: () => void;
  /** 選択中の資産の相対パス。未選択は空文字。 */
  selectedPath: string;
}

/** 資産フォルダのパス末尾の名前だけを取り出す(区切りは / \ の両方を許す)。 */
function folderName(path: string): string {
  const trimmed = path.replace(/[\\/]+$/, "");
  const segments = trimmed.split(/[\\/]/);
  return segments[segments.length - 1] || trimmed;
}

/**
 * 側パネルの資産一覧。資産フォルダの選択・再解析・端末取込と、絞り込み、そしてツリーを持つ。
 */
export function SidePanel({
  onSelectFolder,
  onReanalyze,
  onImport,
  selectedPath,
}: SidePanelProps): ReactElement {
  const project = useProject();
  const dispatch = useWorkbenchDispatch();
  const [search, setSearch] = useState("");
  const [type, setType] = useState<AssetTypeFilter>("all");
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());

  const items = artifactItems(project.inventory);
  const findingCounts = useMemo(
    () => countFindingsByFile(artifactItems(project.findings), artifactItems(project.sqlAdvice)),
    [project.findings, project.sqlAdvice],
  );
  const rows = useMemo(
    () => buildTreeRows(items, { search, type, collapsed, findingCounts }),
    [items, search, type, collapsed, findingCounts],
  );
  const notices = useMemo(
    () => (project.mode === "results" ? scanNotices(project.scanDiscovery, items.length) : []),
    [project.mode, project.scanDiscovery, items.length],
  );

  const running = project.mode === "running";
  const heading = project.inputDir === null ? "資産フォルダ" : folderName(project.inputDir);

  return (
    <div className="ci-side" data-testid="sidepanel">
      <div className="ci-side__head">
        <h2 className="ci-side__title" title={project.inputDir ?? undefined}>
          {heading}
        </h2>
        <div className="ci-side__actions">
          <Button onClick={onSelectFolder} disabled={running} data-testid="select-folder">
            フォルダを選ぶ
          </Button>
          <Button
            variant="primary"
            onClick={onReanalyze}
            disabled={running || project.inputDir === null}
            data-testid="reanalyze"
          >
            再解析
          </Button>
          <Button
            onClick={onImport}
            disabled={running || project.inputDir === null}
            data-testid="import-source"
          >
            端末取込
          </Button>
        </div>
      </div>

      {project.inputDir === null ? (
        <EmptyState
          icon="＋"
          title="資産フォルダを選んでください"
          description="フォルダの名前や並びの決まりはありません。"
          actionLabel="フォルダを選ぶ"
          onAction={onSelectFolder}
        />
      ) : running ? (
        <RunningIndicator
          title="資産を解析しています…"
          stages={RUN_STAGES}
          activeStage={project.runStage}
        />
      ) : (
        <>
          <div className="ci-side__filters">
            <TextInput
              aria-label="資産の絞り込み"
              placeholder="名前・フォルダで絞る"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <ChipRadioGroup
              label="種別で絞る"
              options={ASSET_TYPE_FILTERS}
              value={type}
              labelOf={assetTypeFilterLabel}
              onChange={setType}
            />
          </div>

          {project.inventory.status === "error" ? (
            <div className="ci-banner ci-banner--error" role="alert">
              走査に失敗しました。{project.inventory.message}
            </div>
          ) : null}

          {notices.map((notice) => (
            <details key={notice.text} className="ci-side__notice">
              <summary>{notice.text}</summary>
              {notice.details.length > 0 ? (
                <ul className="ci-side__notice-list">
                  {notice.details.map((detail) => (
                    <li key={detail}>{detail}</li>
                  ))}
                </ul>
              ) : null}
            </details>
          ))}

          {rows.length === 0 ? (
            <p className="ci-side__blank">
              {items.length === 0
                ? "まだ解析していません。"
                : "絞り込みに合う資産がありません。"}
            </p>
          ) : (
            <AssetTree
              rows={rows}
              selectedPath={selectedPath}
              onOpenFile={(path) => dispatch({ type: "OPEN_TAB", tab: sourceTab(path) })}
              onToggleFolder={(path) => setCollapsed((current) => toggleCollapsed(current, path))}
            />
          )}
        </>
      )}
    </div>
  );
}
