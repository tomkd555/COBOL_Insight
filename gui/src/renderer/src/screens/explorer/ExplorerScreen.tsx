import { useEffect, useMemo, useState, type ReactElement } from "react";
import type { AssetTypeFilter } from "../../state/appState";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { deriveRunBanner } from "../../state/status";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SCREEN_META } from "../screenMeta";
import { ExplorerToolbar } from "./ExplorerToolbar";
import { AssetList } from "./AssetList";
import { AssetDetail } from "./AssetDetail";
import { toSourcePreview, type SourcePreview } from "./sourcePreview";
import { disabledRuleIds as disabledRules } from "../settings/settingsModel";
import {
  buildAssetGroups,
  countFindingsByFile,
  encodingSelectValue,
  previewCodepage,
  toCodepageOverrides,
} from "./assetView";

/** デコードプレビューの表示行数(design のプレビュー枠は先頭5行)。 */
const PREVIEW_LINES = 5;

/** 例外・非 Error 値から表示用の文言を取り出す。 */
function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * 資産エクスプローラー(scan)。上部ツールバー(インポート/名前フィルタ/種別チップ/解析実行)、
 * 中央の資産一覧、右 330px の詳細ペインを組む。
 *
 * 「▶ 解析実行」は解析の単一の起点であり、scan・lint・sql-advise を順に起動して、資産一覧・
 * 指摘・SQL助言を AppState へ収める。したがってタブを移動しても結果は失われず、指摘一覧・
 * SQL助言の各画面は再起動せずにこの結果を表示する。段ごとの失敗は空の結果に潰さず、失敗として
 * バナーと各画面へ伝える。empty/running/results/error の4状態を描き分ける。
 */
export function ExplorerScreen(): ReactElement {
  const state = useAppState();
  const dispatch = useAppDispatch();

  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());

  const meta = SCREEN_META.explorer;
  const analyzed = state.mode === "results" || state.mode === "error";
  const items = state.inventory.status === "ready" ? state.inventory.items : [];

  const findingCounts = useMemo(
    () => (state.findings.status === "ready" ? countFindingsByFile(state.findings.items) : {}),
    [state.findings],
  );

  const groups = useMemo(
    () =>
      buildAssetGroups(items, {
        search: state.assetSearch,
        type: state.assetType,
        encodingSel: state.encodingSel,
        mode: state.mode,
        selectedPath: state.selectedAsset,
        findingCounts,
      }),
    [items, state.assetSearch, state.assetType, state.encodingSel, state.mode, state.selectedAsset, findingCounts],
  );

  const selectedItem = useMemo(
    () => items.find((entry) => entry.path === state.selectedAsset) ?? null,
    [items, state.selectedAsset],
  );

  const [preview, setPreview] = useState<SourcePreview>({ status: "idle" });
  const inputDir = state.project.inputDir;
  const selectedPath = selectedItem === null ? null : selectedItem.path;
  const selectedCodepage =
    selectedItem === null
      ? null
      : previewCodepage(selectedItem, state.encodingSel, state.defaultEncoding);

  // 選択資産または文字コード指定が変わるたびに、main の readSourceText で先頭数行を取り直す。
  // 応答が返る前に選択が変わった場合は、古い応答を捨てる。
  useEffect(() => {
    if (selectedPath === null || inputDir === null) {
      setPreview({ status: "idle" });
      return;
    }
    let current = true;
    setPreview({ status: "loading" });
    window.cobolInsight
      .readSourceText({
        inputDir,
        path: selectedPath,
        codepage: selectedCodepage,
        maxLines: PREVIEW_LINES,
      })
      .then((result) => {
        if (current) setPreview(toSourcePreview(result));
      })
      .catch((error: unknown) => {
        if (current) setPreview({ status: "error", message: messageOf(error) });
      });
    return () => {
      current = false;
    };
  }, [inputDir, selectedPath, selectedCodepage]);

  /** 資産フォルダを選び、プロジェクトの入力フォルダとして共有する。キャンセル時は何も変えない。 */
  async function onImport(): Promise<void> {
    const selected = await window.cobolInsight.selectInputFolder();
    if (selected === null) return;
    dispatch({ type: "SET_PROJECT", project: { inputDir: selected } });
    dispatch({
      type: "SHOW_TOAST",
      message: `資産フォルダを取り込みました（${selected}）。「▶ 解析実行」で解析を開始します。`,
    });
  }

  /** scan を起動し、SQLite から資産一覧を読む。失敗を呼び出し側へ真偽で返す。 */
  async function runScanStage(inputDir: string): Promise<boolean> {
    try {
      const result = await window.cobolInsight.runScan({
        inputDir,
        copybookPaths: state.project.copybookPaths,
        codepageOverrides: toCodepageOverrides(state.encodingSel),
      });
      const db = result.outputs.db;
      const inventory = db === undefined ? [] : await window.cobolInsight.readAssetInventory(db);
      dispatch({ type: "SET_INVENTORY", result: { status: "ready", items: inventory }, dbPath: db });
      // 非ゼロ終了は構文解析の失敗を含む部分的成功であり、一覧は利用できる。
      return result.exitCode !== 0;
    } catch (error) {
      dispatch({ type: "SET_INVENTORY", result: { status: "error", message: messageOf(error) } });
      return true;
    }
  }

  /** lint を起動し、SARIF から指摘を読む。 */
  async function runLintStage(inputDir: string): Promise<boolean> {
    try {
      const result = await window.cobolInsight.runLint({
        inputDir,
        copybookPaths: state.project.copybookPaths,
        disabledRules: disabledRules(state.rulesDisabled),
      });
      const sarif = result.outputs.sarif;
      if (sarif === undefined) {
        throw new Error("lint が SARIF を出力しませんでした。");
      }
      const findings = await window.cobolInsight.readSarif(sarif);
      dispatch({ type: "SET_FINDINGS", result: { status: "ready", items: findings } });
      return false;
    } catch (error) {
      dispatch({ type: "SET_FINDINGS", result: { status: "error", message: messageOf(error) } });
      return true;
    }
  }

  /** sql-advise を起動し、SARIF から SQL 助言を読む。 */
  async function runSqlAdviseStage(inputDir: string): Promise<boolean> {
    try {
      const result = await window.cobolInsight.runSqlAdvise({
        inputDir,
        copybookPaths: state.project.copybookPaths,
        // S001〜S006 も設定で無効化できるため、sql-advise へも --disable-rule を渡す。
        disabledRules: disabledRules(state.rulesDisabled),
      });
      const sarif = result.outputs.sarif;
      if (sarif === undefined) {
        throw new Error("sql-advise が SARIF を出力しませんでした。");
      }
      const advice = await window.cobolInsight.readSarif(sarif);
      dispatch({ type: "SET_SQL_ADVICE", result: { status: "ready", items: advice } });
      return false;
    } catch (error) {
      dispatch({ type: "SET_SQL_ADVICE", result: { status: "error", message: messageOf(error) } });
      return true;
    }
  }

  /**
   * 解析の単一の起点。scan → lint → sql-advise の順に起動し、段の進行を SET_RUN_STAGE で
   * 実行中インジケータへ伝える(START_RUN が第1段から始める)。
   */
  async function onRun(): Promise<void> {
    if (inputDir === null) return;
    dispatch({ type: "START_RUN" });
    const scanFailed = await runScanStage(inputDir);
    dispatch({ type: "SET_RUN_STAGE", stage: 2 });
    const lintFailed = await runLintStage(inputDir);
    dispatch({ type: "SET_RUN_STAGE", stage: 3 });
    const sqlFailed = await runSqlAdviseStage(inputDir);
    const failed = scanFailed || lintFailed || sqlFailed;
    dispatch({
      type: "FINISH_RUN",
      failed,
      toast: failed ? undefined : "解析が完了しました。",
    });
  }

  function onToggleDir(dir: string): void {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(dir)) next.delete(dir);
      else next.add(dir);
      return next;
    });
  }

  function onEncodingChange(value: string): void {
    if (selectedItem === null) return;
    dispatch({ type: "SET_ASSET_ENCODING", path: selectedItem.path, encoding: value });
    dispatch({ type: "SHOW_TOAST", message: "文字コードを変更しました。次回の解析実行で反映されます。" });
  }

  const encodingValue =
    selectedItem === null
      ? ""
      : encodingSelectValue(selectedItem, state.encodingSel, state.defaultEncoding);
  const banner = deriveRunBanner(state);

  return (
    <div className="ci-explorer">
      <div className="ci-explorer__main">
        <ExplorerToolbar
          search={state.assetSearch}
          onSearchChange={(value) => dispatch({ type: "SET_ASSET_SEARCH", value })}
          typeFilter={state.assetType}
          onTypeChange={(value: AssetTypeFilter) => dispatch({ type: "SET_ASSET_TYPE", value })}
          onImport={() => void onImport()}
          onRun={() => void onRun()}
          runDisabled={state.mode === "running" || inputDir === null}
        />
        {banner === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {banner}
          </div>
        )}
        <div className="ci-explorer__content">
          {state.mode === "empty" ? (
            <EmptyState
              icon={meta.emptyIcon}
              title={meta.emptyTitle}
              description={meta.emptyDesc}
              actionLabel={meta.emptyAction}
              onAction={() => void onImport()}
              note={meta.emptyNote}
            />
          ) : state.mode === "running" ? (
            <RunningIndicator title={meta.runningTitle} activeStage={state.runStage} />
          ) : (
            <AssetList
              groups={groups}
              collapsed={collapsed}
              showFindingColumn={analyzed}
              onToggleDir={onToggleDir}
              onSelect={(path) => dispatch({ type: "SELECT_ASSET", path })}
            />
          )}
        </div>
      </div>
      <AssetDetail
        item={selectedItem}
        mode={state.mode}
        findingCount={selectedItem === null ? 0 : (findingCounts[selectedItem.path] ?? 0)}
        encodingValue={encodingValue}
        onEncodingChange={onEncodingChange}
        preview={preview}
      />
    </div>
  );
}
