import type { ReactElement } from "react";
import { codepageLabel } from "../data/encodings";
import { artifactCount, artifactItems, useProject } from "../state/projectStore";
import { activeTabOf, useWorkbench, useWorkbenchDispatch } from "../state/workbenchStore";

export interface StatusBarProps {
  /** 本文領域のカーソル位置。資産を開いていないときは null。 */
  cursor: { readonly line: number; readonly column: number } | null;
}

/** 成果物の件数。取得済みは件数、それ以外(実行中・未取得・失敗)は「―」。 */
function countText(count: number | null, running: boolean): string {
  return running || count === null ? "―" : String(count);
}

/** 解析の状態を1語で示す。 */
function modeText(mode: string): string {
  switch (mode) {
    case "running":
      return "解析実行中";
    case "results":
      return "解析完了";
    case "error":
      return "一部の解析に失敗";
    default:
      return "解析待ち";
  }
}

/**
 * 最下部のステータスバー。左にプロジェクトの場所と解析の状態、右に開いている資産の文字コード・
 * カーソル位置と件数の集計を出す。下部パネルの開閉もここから行う。
 */
export function StatusBar({ cursor }: StatusBarProps): ReactElement {
  const project = useProject();
  const workbench = useWorkbench();
  const dispatch = useWorkbenchDispatch();
  const running = project.mode === "running";

  const tab = activeTabOf(workbench);
  const item =
    tab?.path === undefined || tab.path === null
      ? null
      : (artifactItems(project.inventory).find((candidate) => candidate.path === tab.path) ?? null);

  const assets = countText(artifactCount(project.inventory), running);
  const findings = countText(artifactCount(project.findings), running);
  const sqlAdvice = countText(artifactCount(project.sqlAdvice), running);

  return (
    <footer className="ci-statusbar" aria-label="ステータス">
      <span className="ci-statusbar__item" title={project.inputDir ?? undefined}>
        {project.inputDir ?? "資産フォルダ未選択"}
      </span>
      <span className="ci-statusbar__item">{modeText(project.mode)}</span>
      <span className="ci-statusbar__spacer" />
      {item !== null ? (
        <span className="ci-statusbar__item">{codepageLabel(item.codepage)}</span>
      ) : null}
      {cursor !== null ? (
        <span className="ci-statusbar__item">
          行 {cursor.line}、列 {cursor.column}
        </span>
      ) : null}
      <button
        type="button"
        className="ci-statusbar__button"
        aria-pressed={workbench.bottomVisible}
        onClick={() => dispatch({ type: "TOGGLE_BOTTOM" })}
      >
        指摘 {findings}・SQL {sqlAdvice}
      </button>
      <span className="ci-statusbar__item">資産 {assets}</span>
      <span className="ci-statusbar__item">v{project.version}</span>
    </footer>
  );
}
