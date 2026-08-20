import { useEffect, useMemo, useState, type CSSProperties, type ReactElement } from "react";
import { SPLIT_PANES, type AssetTypeFilter, type RunStage } from "../../state/appState";
import { useAppState, useAppDispatch } from "../../state/AppStateContext";
import { deriveRunBanner, deriveScanNotice, type ScanNoticeSection } from "../../state/status";
import { messageOf, runAnalysis, type AnalysisStage } from "../../services/analysis";
import { EmptyState } from "../../components/EmptyState";
import { RunningIndicator } from "../../components/RunningIndicator";
import { SplitHandle } from "../../components/SplitHandle";
import { SCREEN_META } from "../screenMeta";
import { ExplorerToolbar } from "./ExplorerToolbar";
import { AssetList } from "./AssetList";
import { AssetDetail } from "./AssetDetail";
import { toSourcePreview, type SourcePreview } from "./sourcePreview";
import {
  buildAssetGroups,
  countFindingsByFile,
  encodingSelectValue,
  previewCodepage,
  toCodepageOverrides,
} from "./assetView";

/** 資産をまだ取り込んでいないときの誘導。取込は解析の起点でもあるため、主アクションを1つだけ置く。 */
const EMPTY_STATE = {
  icon: "＋",
  title: "資産がまだ取り込まれていません",
  description: "資産フォルダを取り込むと、走査と解析をそのまま実行する。",
  actionLabel: "フォルダを取り込む",
  note: "文字コードは自動判定（Shift_JIS / UTF-8）または推定（EBCDIC CP930/939）。",
} as const;

/** 解析の段を、実行中インジケータが示す番号へ対応づける。 */
const STAGE_NUMBER: Record<AnalysisStage, RunStage> = { scan: 1, lint: 2, sqlLint: 3 };

/**
 * 資産エクスプローラー(scan)。上部ツールバー(インポート/名前フィルタ/種別チップ/解析実行)、
 * 中央の資産一覧、右 330px の詳細ペインを組む。
 *
 * 「▶ 解析実行」は解析の単一の起点であり、scan・lint・sql-lint を順に起動して、資産一覧・
 * 指摘・SQL指摘を AppState へ収める。したがってタブを移動しても結果は失われず、指摘一覧・
 * SQL指摘の各画面は再起動せずにこの結果を表示する。段ごとの失敗は空の結果に潰さず、失敗として
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

  /**
   * 資産フォルダを選び、そのまま解析へ進む。キャンセル時は何も変えない。
   *
   * 取込だけでは画面が空状態のまま変わらないため、取り込めたのかどうかが利用者へ伝わらない。
   * 取込を解析の起点とし、選んだ直後から実行中インジケータで進行を示す。
   */
  async function onImport(): Promise<void> {
    let selected: string | null;
    try {
      selected = await window.cobolInsight.selectInputFolder();
    } catch (error) {
      dispatch({
        type: "SHOW_TOAST",
        message: `資産フォルダを取り込めなかった（${messageOf(error)}）。`,
      });
      return;
    }
    if (selected === null) return;
    dispatch({ type: "SET_PROJECT", project: { inputDir: selected } });
    await onRun(selected);
  }

  /**
   * 解析の起点。手順そのものは services/analysis が持ち、この画面は段の進行と結果を
   * AppState へ流し込むだけである。
   */
  async function onRun(dir: string): Promise<void> {
    dispatch({ type: "START_RUN" });
    const failed = await runAnalysis(
      {
        inputDir: dir,
        copybookPaths: state.project.copybookPaths,
        codepageOverrides: toCodepageOverrides(state.encodingSel),
      },
      {
        onStage: (stage) => dispatch({ type: "SET_RUN_STAGE", stage: STAGE_NUMBER[stage] }),
        onInventory: (result, dbPath, discovery) =>
          dispatch({
            type: "SET_INVENTORY",
            result,
            ...(dbPath === null ? {} : { dbPath }),
            ...(discovery === null ? {} : { discovery }),
          }),
        onFindings: (result) => dispatch({ type: "SET_FINDINGS", result }),
        onSqlAdvice: (result) => dispatch({ type: "SET_SQL_ADVICE", result }),
      },
    );
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
  const scanNoticeSections = deriveScanNotice(state);
  const detailWidth = state.paneWidths.explorerDetail;
  // 詳細ペインの幅と、一覧へ必ず残す最小を CSS カスタムプロパティで渡す(寸法の指定は CSS 側に置く)。
  // 最小は SPLIT_PANES の oppositeMin をそのまま流し、上限の値を CSS 側の定数として二重に持たない。
  const paneStyle = {
    "--ci-explorer-detail-w": `${detailWidth}px`,
    "--ci-opposite-min": `${SPLIT_PANES.explorerDetail.oppositeMin}px`,
  } as CSSProperties;

  return (
    <div className="ci-explorer" style={paneStyle}>
      <div className="ci-explorer__main">
        <ExplorerToolbar
          search={state.assetSearch}
          onSearchChange={(value) => dispatch({ type: "SET_ASSET_SEARCH", value })}
          typeFilter={state.assetType}
          onTypeChange={(value: AssetTypeFilter) => dispatch({ type: "SET_ASSET_TYPE", value })}
          onImport={() => void onImport()}
          onRun={() => {
            if (inputDir !== null) void onRun(inputDir);
          }}
          runDisabled={state.mode === "running" || inputDir === null}
        />
        {banner === null ? null : (
          <div className="ci-banner ci-banner--error" role="alert">
            {banner}
          </div>
        )}
        {scanNoticeSections.length === 0 ? null : (
          <div className="ci-banner ci-banner--warn" role="status">
            {scanNoticeSections.map((section, index) => (
              <ScanNoticeItem key={index} section={section} />
            ))}
          </div>
        )}
        <div className="ci-explorer__content">
          {state.mode === "empty" ? (
            <EmptyState
              icon={EMPTY_STATE.icon}
              title={EMPTY_STATE.title}
              description={EMPTY_STATE.description}
              actionLabel={EMPTY_STATE.actionLabel}
              onAction={() => void onImport()}
              note={EMPTY_STATE.note}
            />
          ) : (
            <>
              {state.mode === "running" ? (
                <div className="ci-explorer__running">
                  <RunningIndicator title={meta.runningTitle} activeStage={state.runStage} />
                </div>
              ) : null}
              <AssetList
                groups={groups}
                collapsed={collapsed}
                showFindingColumn={analyzed}
                onToggleDir={onToggleDir}
                onSelect={(path) => dispatch({ type: "SELECT_ASSET", path })}
              />
            </>
          )}
        </div>
      </div>
      <SplitHandle
        size={detailWidth}
        min={SPLIT_PANES.explorerDetail.min}
        oppositeMin={SPLIT_PANES.explorerDetail.oppositeMin}
        onSizeChange={(width) => dispatch({ type: "SET_PANE_WIDTH", pane: "explorerDetail", width })}
        onCommit={() => dispatch({ type: "COMMIT_PANE_SIZE" })}
        ariaLabel="資産の詳細ペインの幅"
      />
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

/**
 * 走査の警告1件。該当ファイルの一覧は engine が全件返すため件数を切らず、`<details>` で畳んで
 * 全件を添える(畳むのは表示の整理であって取りこぼしではない)。
 */
function ScanNoticeItem({ section }: { section: ScanNoticeSection }): ReactElement {
  return (
    <div className="ci-scan-notice">
      <p className="ci-scan-notice__text">{section.text}</p>
      {section.details.length === 0 ? null : (
        <details className="ci-scan-notice__details">
          <summary>該当ファイル {section.details.length} 件</summary>
          <ul className="ci-scan-notice__list">
            {section.details.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        </details>
      )}
    </div>
  );
}
