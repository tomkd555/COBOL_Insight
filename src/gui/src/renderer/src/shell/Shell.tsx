import { useCallback, useEffect, useState, type ReactElement } from "react";
import { TitleBar } from "./TitleBar";
import { ProgressBar } from "./ProgressBar";
import { StatusBar } from "./StatusBar";
import { ActivityBar } from "./ActivityBar";
import { EditorArea } from "./EditorArea";
import { shortcutOf } from "./shortcuts";
import { confirmClose } from "./closeGuard";
import { Toast } from "../components/Toast";
import { SplitHandle } from "../components/SplitHandle";
import { SidePanel } from "../sidebar/SidePanel";
import { BottomPanel } from "../panel/BottomPanel";
import { ImportDialog } from "../dialogs/ImportDialog";
import { RUN_STAGES } from "../components/RunningIndicator";
import { messageOf, runAnalysis } from "../services/analysis";
import { loadRuleCatalog, restoreSettings, saveSettings } from "../services/appSetup";
import { charsetOf } from "../data/encodings";
import { useProject, useProjectDispatch } from "../state/projectStore";
import { useSettings, useSettingsDispatch } from "../state/settingsStore";
import {
  BOTTOM_PANEL_LIMITS,
  SIDE_PANEL_LIMITS,
  activeTabOf,
  isTabDirty,
  sourceTab,
  useWorkbench,
  useWorkbenchDispatch,
} from "../state/workbenchStore";

/** 保存する寸法のキー。settings.json の paneSizes に入る。 */
const SIDE_SIZE_KEY = "side";
const BOTTOM_SIZE_KEY = "bottom";

/** RUN_STAGES の文言から「第N段 」の接頭辞を外す。 */
function stageLabel(stage: number): string {
  return (RUN_STAGES[stage - 1] ?? "").replace(/^第\d段\s*/u, "");
}

/** カーソル位置。本文を開いていないときは null。 */
interface CursorPosition {
  readonly line: number;
  readonly column: number;
}

/**
 * アプリのシェル。上からタイトルバー・本体(アクティビティバー / 側パネル / 本文と下部パネル)・
 * ステータスバーの3段で組む。engine の起動と設定の保存はここが受け持ち、各領域は状態を読むだけである。
 */
export function Shell(): ReactElement {
  const project = useProject();
  const projectDispatch = useProjectDispatch();
  const settings = useSettings();
  const settingsDispatch = useSettingsDispatch();
  const workbench = useWorkbench();
  const workbenchDispatch = useWorkbenchDispatch();

  const [toast, setToast] = useState<string | null>(null);
  const [cursor, setCursor] = useState<CursorPosition | null>(null);
  const [importOpen, setImportOpen] = useState(false);

  const running = project.mode === "running";
  const activeTab = activeTabOf(workbench);
  const selectedPath = activeTab?.path ?? "";

  // ルール名・説明の供給源は解析エンジンであり、画面は持たない。起動時に1回取り込む。
  useEffect(() => {
    loadRuleCatalog(projectDispatch).catch((error: unknown) => {
      setToast(`ルールの一覧を読み込めませんでした。${messageOf(error)}`);
    });
  }, [projectDispatch]);

  // 前回の設定を起動時に戻す。読めなくても既定のまま使えるため、伝えるに留める。
  useEffect(() => {
    restoreSettings(settingsDispatch)
      .then((stored) => {
        workbenchDispatch({
          type: "RESTORE_SIZES",
          sideWidth: stored.paneSizes[SIDE_SIZE_KEY],
          bottomHeight: stored.paneSizes[BOTTOM_SIZE_KEY],
        });
      })
      .catch((error: unknown) => {
        setToast(`保存した設定を読めませんでした。${messageOf(error)}`);
      });
  }, [settingsDispatch, workbenchDispatch]);

  // 設定の値と領域の寸法が変わるたびに保存する。復元を終える前は、初期値で保存済みの設定を
  // 上書きしてしまうため書かない。寸法はドラッグ中の1画素ごとではなく、操作の完了だけを見る。
  const settingsLoaded = settings.loaded;
  const sizeCommitCount = workbench.sizeCommitCount;
  useEffect(() => {
    if (!settingsLoaded) {
      return;
    }
    saveSettings(settings, {
      [SIDE_SIZE_KEY]: workbench.sideWidth,
      [BOTTOM_SIZE_KEY]: workbench.bottomHeight,
    }).catch((error: unknown) => {
      setToast(`設定を保存できませんでした。${messageOf(error)}`);
    });
  }, [
    settingsLoaded,
    settings.severityThreshold,
    settings.defaultEncoding,
    settings.copybookPaths,
    sizeCommitCount,
  ]);

  /** 資産フォルダを解析する。走査・指摘・SQL指摘の3段を順に走らせる。 */
  const analyze = useCallback(
    async (inputDir: string): Promise<void> => {
      projectDispatch({ type: "START_RUN" });
      const overrides: Record<string, string> = {};
      for (const [path, selection] of Object.entries(project.codepageOverrides)) {
        const charset = charsetOf(selection);
        if (charset !== null) overrides[path] = charset;
      }
      const failed = await runAnalysis(
        { inputDir, copybookPaths: [...settings.copybookPaths], codepageOverrides: overrides },
        {
          onStage: (stage) => {
            projectDispatch({
              type: "SET_RUN_STAGE",
              stage: stage === "scan" ? 1 : stage === "lint" ? 2 : 3,
            });
          },
          onInventory: (result, dbPath, discovery) => {
            projectDispatch({ type: "SET_INVENTORY", result, dbPath, discovery });
          },
          onFindings: (result) => projectDispatch({ type: "SET_FINDINGS", result }),
          onSqlAdvice: (result) => projectDispatch({ type: "SET_SQL_ADVICE", result }),
        },
      );
      projectDispatch({ type: "FINISH_RUN", failed });
      setToast(failed ? "解析の一部が失敗しました。実行ログで内容を確かめてください。" : "解析が完了しました。");
    },
    [projectDispatch, project.codepageOverrides, settings.copybookPaths],
  );

  /** 資産フォルダを選び、続けて解析する。 */
  async function selectFolder(): Promise<void> {
    try {
      const selected = await window.cobolInsight.selectInputFolder();
      if (selected === null) return;
      projectDispatch({ type: "SET_INPUT_DIR", inputDir: selected });
      await analyze(selected);
    } catch (error) {
      setToast(`資産フォルダを開けませんでした。${messageOf(error)}`);
    }
  }

  /** 指摘の行から本文を開く。 */
  const openAt = useCallback(
    (path: string, line: number): void => {
      workbenchDispatch({ type: "OPEN_TAB", tab: sourceTab(path, line) });
    },
    [workbenchDispatch],
  );

  // パネルの開閉とタブの移動は画面のどこからでも効かせる。判定は純関数が持つ。
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent): void => {
      const command = shortcutOf(event);
      if (command === null) return;
      event.preventDefault();
      switch (command) {
        case "toggleSide":
          workbenchDispatch({ type: "TOGGLE_SIDE" });
          break;
        case "toggleBottom":
          workbenchDispatch({ type: "TOGGLE_BOTTOM" });
          break;
        case "nextTab":
          workbenchDispatch({ type: "STEP_TAB", step: 1 });
          break;
        case "previousTab":
          workbenchDispatch({ type: "STEP_TAB", step: -1 });
          break;
        case "closeTab": {
          const id = workbench.activeTabId;
          if (id !== null && confirmClose(isTabDirty(workbench, id))) {
            workbenchDispatch({ type: "CLOSE_TAB", id });
          }
          break;
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [workbenchDispatch, workbench]);

  // 本文を閉じたらカーソル位置も消す。前のタブの位置がステータスバーへ残らないようにする。
  useEffect(() => {
    if (activeTab === null || activeTab.kind !== "source") {
      setCursor(null);
    }
  }, [activeTab]);

  return (
    <div className="ci-shell">
      <TitleBar
        isRunning={running}
        runningLabel={running ? stageLabel(project.runStage) : undefined}
        onCancelRun={
          running
            ? () => {
                // 画面の待機状態を解くだけでは engine が走り続け、次の起動と二重に動く。
                void window.cobolInsight.cancelRun();
                projectDispatch({ type: "CANCEL_RUN" });
              }
            : undefined
        }
      />
      {running ? <ProgressBar /> : null}

      <div className="ci-shell__body">
        <ActivityBar />
        {workbench.sideVisible ? (
          <>
            <div className="ci-shell__side" style={{ width: `${workbench.sideWidth}px` }}>
              <SidePanel
                selectedPath={selectedPath}
                onSelectFolder={() => void selectFolder()}
                onReanalyze={() => {
                  if (project.inputDir !== null) void analyze(project.inputDir);
                }}
                onImport={() => setImportOpen(true)}
              />
            </div>
            <SplitHandle
              size={workbench.sideWidth}
              min={SIDE_PANEL_LIMITS.min}
              oppositeMin={SIDE_PANEL_LIMITS.oppositeMin}
              side="before"
              ariaLabel="側パネルの幅"
              onSizeChange={(width) => workbenchDispatch({ type: "SET_SIDE_WIDTH", width })}
              onCommit={() => workbenchDispatch({ type: "COMMIT_SIZE" })}
            />
          </>
        ) : null}

        <div className="ci-shell__main">
          <EditorArea onCursor={(line, column) => setCursor({ line, column })} />
          {workbench.bottomVisible ? (
            <>
              <SplitHandle
                size={workbench.bottomHeight}
                min={BOTTOM_PANEL_LIMITS.min}
                oppositeMin={BOTTOM_PANEL_LIMITS.oppositeMin}
                orientation="horizontal"
                side="after"
                ariaLabel="下部パネルの高さ"
                onSizeChange={(height) => workbenchDispatch({ type: "SET_BOTTOM_HEIGHT", height })}
                onCommit={() => workbenchDispatch({ type: "COMMIT_SIZE" })}
              />
              <div className="ci-shell__bottom" style={{ height: `${workbench.bottomHeight}px` }}>
                <BottomPanel onOpen={openAt} />
              </div>
            </>
          ) : null}
        </div>
      </div>

      <Toast message={toast} onDismiss={() => setToast(null)} />
      <StatusBar cursor={cursor} />

      {importOpen && project.inputDir !== null ? (
        <ImportDialog
          inputDir={project.inputDir}
          onClose={() => setImportOpen(false)}
          onSaved={(relPath, lineCount) => {
            setImportOpen(false);
            projectDispatch({ type: "LOG", text: `${relPath} へ ${lineCount} 行を保存しました。` });
            setToast(`${relPath} へ保存しました。「再解析」で解析へ反映します。`);
          }}
        />
      ) : null}
    </div>
  );
}
