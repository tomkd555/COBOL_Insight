/**
 * ステータスバー文言と解析実行の失敗バナーの導出。design gvGlobal(design:1099-1104)の mode 別
 * statusLeft/statusCounts を移植する。件数は解析実行が得た成果物(inventory=scan・findings=lint・
 * sqlAdvice=sql-advise)の実件数から取り、実行中・未取得・取得失敗は「―」で示して 0 件と混同させない。
 * ルールの有効数は設定で無効化した集合(rulesDisabled)から導く。
 */

import { enabledRuleCount } from "../screens/settings/settingsModel";
import type { AppState, ArtifactState } from "./appState";

export interface StatusText {
  readonly left: string;
  readonly counts: string;
}

/** 成果物の件数表示。取得済みは件数、それ以外(実行中・未取得・失敗)は「―」。 */
function countText<T>(result: ArtifactState<T>, running: boolean): string {
  if (running || result.status !== "ready") {
    return "―";
  }
  return String(result.items.length);
}

export function deriveStatus(state: AppState): StatusText {
  const { version } = state;
  // 有効なルール数は設定の無効化集合から導く。固定値は持たない。
  const rulesEnabled = enabledRuleCount(state.rulesDisabled);

  if (state.mode === "empty") {
    return {
      left: "準備完了 ― 資産をインポートしてください",
      counts: `資産 0 ・ ルール ${rulesEnabled} 有効 ・ v${version}`,
    };
  }

  const running = state.mode === "running";
  const left = running
    ? "解析実行中… ローカル解析ジョブ(Java) ― キャンセル可能"
    : state.mode === "error"
      ? "解析完了 ― 部分的成功（失敗した処理があります）"
      : "解析完了 ― 正常終了";

  const assets = countText(state.inventory, running);
  const findings = countText(state.findings, running);
  const sqlAdvice = countText(state.sqlAdvice, running);

  return {
    left,
    counts: `資産 ${assets} ・ 指摘 ${findings} ・ SQL助言 ${sqlAdvice} ・ ルール ${rulesEnabled} 有効 ・ v${version}`,
  };
}

/**
 * 資産エクスプローラーに出す解析実行の失敗バナー文。失敗した段の内容を具体的に示し、
 * どの段も失敗していない error モード(scan の非ゼロ終了)は構文解析の部分的失敗として示す。
 * 失敗が無ければ null を返す。
 */
export function deriveRunBanner(state: AppState): string | null {
  if (state.mode !== "error") {
    return null;
  }
  if (state.inventory.status === "error") {
    return `解析に失敗しました。${state.inventory.message}`;
  }
  const parts: string[] = [];
  if (state.findings.status === "error") {
    parts.push(`指摘の取得に失敗しました。${state.findings.message}`);
  }
  if (state.sqlAdvice.status === "error") {
    parts.push(`SQL助言の取得に失敗しました。${state.sqlAdvice.message}`);
  }
  if (parts.length > 0) {
    return parts.join(" ");
  }
  return "一部の資産で構文解析に失敗しました。失敗した資産は一覧に表示され、他の資産の結果は利用できます（部分的な結果）。";
}
