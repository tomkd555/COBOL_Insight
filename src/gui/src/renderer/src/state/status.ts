/**
 * ステータスバー文言と解析実行の失敗バナーの導出。design gvGlobal の mode 別
 * statusLeft/statusCounts を移植する。件数は解析実行が得た成果物(inventory=scan・findings=lint・
 * sqlAdvice=sql-lint)の実件数から取り、実行中・未取得・取得失敗は「―」で示して 0 件と混同させない。
 * ルールの有効数は設定で無効化した集合(rulesDisabled)から導く。
 */

import { enabledRuleCount } from "../screens/settings/settingsModel";
import type { AppState, ArtifactState, ScanDiscovery } from "./appState";

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
      left: "準備完了 ― 資産の取込待ち",
      counts: `資産 0 ・ ルール ${rulesEnabled} 有効 ・ v${version}`,
    };
  }

  const running = state.mode === "running";
  // 失敗の内訳は deriveRunBanner のバナーが述べるため、ここは状態だけを示す。
  const left = running
    ? "解析実行中…"
    : state.mode === "error"
      ? "解析完了 ― 一部が失敗"
      : "解析完了 ― 正常終了";

  const assets = countText(state.inventory, running);
  const findings = countText(state.findings, running);
  const sqlAdvice = countText(state.sqlAdvice, running);

  return {
    left,
    counts: `資産 ${assets} ・ 指摘 ${findings} ・ SQL指摘 ${sqlAdvice} ・ ルール ${rulesEnabled} 有効 ・ v${version}`,
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
    return `解析に失敗した。${state.inventory.message}`;
  }
  const parts: string[] = [];
  if (state.findings.status === "error") {
    parts.push(`指摘の取得に失敗した。${state.findings.message}`);
  }
  if (state.sqlAdvice.status === "error") {
    parts.push(`SQL指摘の取得に失敗した。${state.sqlAdvice.message}`);
  }
  if (parts.length > 0) {
    return parts.join(" ");
  }
  return "一部の資産で構文解析に失敗した。失敗した資産は一覧に表示され、他の資産の結果は利用できる（部分的な結果）。";
}

/** 走査の仕方ごとに、engine が資産として認識する拡張子。 */
const RECOGNIZED_EXTENSIONS: Record<ScanDiscovery["mode"], string> = {
  convention: ".cbl / .cpy / .jcl / .bms",
  recursive: ".cbl / .cob / .cobol / .cpy / .copy / .jcl / .bms",
};

/**
 * 資産エクスプローラーに出す走査の警告文。解析は成功したが取り込めていないものがある場合を
 * 扱う。失敗は {@link deriveRunBanner} の担当で、こちらは失敗扱いにしない（0 件は異常終了では
 * ないため、終了コードもモードも成功のまま警告だけを出す）。警告が要らなければ null を返す。
 */
export function deriveScanNotice(state: AppState): string | null {
  if (state.mode !== "results" || state.inventory.status !== "ready") {
    return null;
  }
  const discovery = state.scanDiscovery;
  if (discovery === null) {
    return null;
  }
  const parts: string[] = [];
  if (state.inventory.items.length === 0) {
    parts.push(
      "対象のファイルが 1 件も見つからなかった。" +
        `選んだフォルダに ${RECOGNIZED_EXTENSIONS[discovery.mode]} のファイルがあるか確認する。`,
    );
  }
  if (discovery.outsideCount > 0) {
    const samples = discovery.outsideSamples.join("、");
    parts.push(
      `資産フォルダの規約（bms・cobol・copy または copybook・jcl の直下）の外にある ` +
        `${discovery.outsideCount} 件は対象外とした（${samples}）。` +
        "取り込むには規約のフォルダへ移すか、そのフォルダを直接選ぶ。",
    );
  }
  if (discovery.truncated) {
    parts.push("走査の上限に達したため、一部のファイルを取り込んでいない。");
  }
  return parts.length === 0 ? null : parts.join(" ");
}
