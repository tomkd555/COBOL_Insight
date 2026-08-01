/**
 * ステータスバー文言と解析実行の失敗バナーの導出。design gvGlobal の mode 別
 * statusLeft/statusCounts を移植する。件数は解析実行が得た成果物(inventory=scan・findings=lint・
 * sqlAdvice=sql-lint)の実件数から取り、実行中・未取得・取得失敗は「―」で示して 0 件と混同させない。
 * ルールの有効数は設定で無効化した集合(rulesDisabled)から導く。
 */

import { enabledRuleCount } from "../screens/settings/settingsModel";
import type { AppState, ArtifactState, DiscoveredAssetKind } from "./appState";

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

/**
 * engine が候補にする既知拡張子。走査の可否はソースの内容で決まり、この一覧は案内文の材料に
 * 限る(engine と GUI の境界の原則。CLAUDE.md「資産の走査」節参照)。
 */
const RECOGNIZED_EXTENSIONS = ".cbl / .cob / .cobol / .cpy / .copy / .jcl / .bms";

/** 走査で判定した種別名から、利用者向けの表示名を引く。 */
const DISCOVERED_ASSET_KIND_LABELS: Readonly<Record<DiscoveredAssetKind, string>> = {
  COBOL: "COBOL 本体",
  COPYBOOK: "コピー句",
  JCL: "JCL",
  BMS: "BMS",
};

/**
 * 走査の警告1件。text が概要、details は該当ファイルの一覧(全件)である。details が空でなければ
 * 画面側は `<details>` で畳んで示す(畳むのは表示の整理であって、件数を切り捨てるわけではない)。
 */
export interface ScanNoticeSection {
  readonly text: string;
  readonly details: readonly string[];
}

const NO_DETAILS: readonly string[] = [];

/**
 * 資産エクスプローラーに出す走査の警告。解析は成功したが取り込めていない・解釈を変えたものが
 * ある場合を扱う。失敗は {@link deriveRunBanner} の担当で、こちらは失敗扱いにしない（0 件は異常
 * 終了ではないため、終了コードもモードも成功のまま警告だけを出す）。報告の優先順位は
 * 「黙って落としたもの(undecided・unreadable) ＞ 黙って解釈を変えたもの(mismatches) ＞
 * 打ち切り(truncated)」。警告が要らなければ空配列を返す。
 */
export function deriveScanNotice(state: AppState): readonly ScanNoticeSection[] {
  if (state.mode !== "results" || state.inventory.status !== "ready") {
    return [];
  }
  const discovery = state.scanDiscovery;
  if (discovery === null) {
    return [];
  }
  const sections: ScanNoticeSection[] = [];
  if (state.inventory.items.length === 0) {
    sections.push({
      text:
        "対象の資産が 1 件も見つからなかった。選んだフォルダに COBOL・コピー句・JCL・BMS の" +
        `ソースがあるか確認する（対応する拡張子の例: ${RECOGNIZED_EXTENSIONS}）。` +
        "拡張子が無くても、内容から種別を判定する。",
      details: NO_DETAILS,
    });
  }
  if (discovery.undecided.length > 0) {
    sections.push({
      text:
        `${discovery.undecided.length} 件は種別を判定できなかったため対象外とした。COBOL 本体なら ` +
        "IDENTIFICATION DIVISION、コピー句ならレベル番号で始まる項目定義、JCL なら // で始まる行、" +
        "BMS なら DFHMSD を含むか確認する。",
      details: discovery.undecided,
    });
  }
  if (discovery.unreadable.length > 0) {
    sections.push({
      text: `${discovery.unreadable.length} 件は読み取れなかったため対象外とした。`,
      details: discovery.unreadable,
    });
  }
  if (discovery.mismatches.length > 0) {
    sections.push({
      text: `${discovery.mismatches.length} 件は拡張子と内容が食い違ったため、内容を優先して取り込んだ。`,
      details: discovery.mismatches.map(
        (mismatch) => `${mismatch.path} → ${DISCOVERED_ASSET_KIND_LABELS[mismatch.byContent]}`,
      ),
    });
  }
  if (discovery.truncated) {
    sections.push({
      text: "走査の上限に達したため、一部のファイルを取り込んでいない。",
      details: NO_DETAILS,
    });
  }
  return sections;
}
