/**
 * 資産エクスプローラーのビューモデル導出。design gvExplorer(design:1107-1178)の
 * 種別写像・種別チップフィルタ・名前フィルタ・ディレクトリ集約・解析状態を、React 非依存の
 * 純関数として持つ。資産の供給源は window.cobolInsight.readAssetInventory が返す
 * AssetInventoryItem(SQLite の SOURCE を NODE.type と結合したもの)であり、指摘件数の供給源は
 * lint の SARIF である(design fileFindCount と同じ数え方)。
 */

import type { AssetInventoryItem, SarifFinding } from "../../../../shared/engine-api";
import type { AssetTypeFilter, ScreenMode } from "../../state/appState";

/** 一覧・チップで用いる表示用の種別(design tyMeta のキー)。 */
export type AssetDisplayType = "JCL" | "COBOL" | "コピー句" | "BMSマップ" | "その他";

/**
 * 文字コード選択肢(design encOpts の自動判定 SJIS/UTF-8 と手動 SJIS/UTF-8/EBCDIC CP930・CP939)に、
 * EBCDIC の推定2件を加えたもの。engine の CodePageDetector は EBCDIC を推定で返すため
 * (design:97 の「推定（EBCDIC CP930/939）」)、検出結果がそのまま選択欄の値になるようにする。
 */
export const ENCODING_OPTIONS: readonly string[] = [
  "自動判定: Shift_JIS",
  "自動判定: UTF-8",
  "推定: EBCDIC CP930",
  "推定: EBCDIC CP939",
  "手動: Shift_JIS",
  "手動: UTF-8",
  "手動: EBCDIC CP930",
  "手動: EBCDIC CP939",
];

/** 手動選択肢 → scan へ渡す charset コード。自動判定・推定は上書きしない(キー不在)。 */
const MANUAL_CHARSET: Record<string, string> = {
  "手動: Shift_JIS": "Shift_JIS",
  "手動: UTF-8": "UTF-8",
  "手動: EBCDIC CP930": "CP930",
  "手動: EBCDIC CP939": "CP939",
};

/**
 * 手動指定の選択肢(ENCODING_OPTIONS の部分集合)。設定画面の既定文字コードもこの語彙から選ぶ。
 * 自動判定・推定は engine の判定結果を表す語であり、人が選ぶ既定値にはならない。
 */
export const MANUAL_ENCODING_OPTIONS: readonly string[] = Object.keys(MANUAL_CHARSET);

/**
 * engine の charset 名(CodePage.charsetName)→ 利用者向けの文字コード表記(裁定 A8)。
 * UTF-8 は engine の値がそのまま利用者向けの表記であるため写像を持たない。
 */
const CODEPAGE_LABELS: Readonly<Record<string, string>> = {
  "WINDOWS-31J": "Shift_JIS",
  "X-IBM930": "EBCDIC CP930",
  "X-IBM939": "EBCDIC CP939",
};

/** 検出コードページに対応する自動判定・推定の選択肢。 */
const DETECTED_SELECTION: Readonly<Record<string, string>> = {
  "WINDOWS-31J": "自動判定: Shift_JIS",
  "X-IBM930": "推定: EBCDIC CP930",
  "X-IBM939": "推定: EBCDIC CP939",
};

/** 写像を持たない検出値(UTF-8 など)に用いる選択欄の値。 */
const FALLBACK_SELECTION = "自動判定: UTF-8";

/** engine の charset 名の別名を大文字へそろえる(SOURCE.codepage は charsetName だが別名も受ける)。 */
function normalizeCodepage(codepage: string): string {
  const upper = codepage.trim().toUpperCase().replace(/[_\s]/g, "-");
  if (upper === "SHIFT-JIS" || upper === "SJIS" || upper === "MS932" || upper === "CP932") {
    return "WINDOWS-31J";
  }
  if (upper === "IBM930" || upper === "CP930") return "X-IBM930";
  if (upper === "IBM939" || upper === "CP939") return "X-IBM939";
  return upper;
}

/** engine の charset 名を利用者向けの表記へ写す。写像を持たない値はそのまま返す(裁定 A8)。 */
export function codepageLabel(codepage: string): string {
  return CODEPAGE_LABELS[normalizeCodepage(codepage)] ?? codepage;
}

/** engine の NODE.type を表示用の種別へ写す。未登録・未知(DATASET 等)は「その他」。 */
export function displayType(nodeType: string): AssetDisplayType {
  switch (nodeType) {
    case "PROGRAM":
      return "COBOL";
    case "JCL":
      return "JCL";
    case "COPYBOOK":
      return "コピー句";
    case "BMS":
      return "BMSマップ";
    default:
      return "その他";
  }
}

/** 種別チップの選択に資産の表示種別が合致するか(design match の種別部)。 */
export function matchesType(type: AssetDisplayType, filter: AssetTypeFilter): boolean {
  if (filter === "すべて") return true;
  if (filter === "BMS") return type === "BMSマップ";
  if (filter === "その他") return type === "その他";
  return type === filter;
}

/** 名前フィルタ(大文字小文字を無視した部分一致)。空文字は全件一致。 */
export function matchesSearch(name: string, search: string): boolean {
  if (search === "") return true;
  return name.toLowerCase().includes(search.toLowerCase());
}

/**
 * 一覧・詳細で表示する文字コード文字列。手動指定(encodingSel)が自動判定より優先し、
 * 未指定なら検出コードページの利用者向け表記、復号失敗(null)なら「未判定」を返す
 * (design encSel[n] || enc)。
 */
export function effectiveEncoding(item: AssetInventoryItem, encodingSel: Record<string, string>): string {
  const manual = encodingSel[item.path];
  if (manual !== undefined) return manual;
  return item.codepage === null ? "未判定" : codepageLabel(item.codepage);
}

/**
 * 文字コード選択欄の現在値。手動指定があればそれ、なければ検出結果に対応する自動判定・推定の
 * 選択肢。検出値は engine の CodePage.charsetName(windows-31j / x-IBM930 / x-IBM939 / UTF-8)が入る。
 * 検出に失敗した資産(codepage=null)は設定の既定文字コード(defaultEncoding)を初期値にする。
 */
export function encodingSelectValue(
  item: AssetInventoryItem,
  encodingSel: Record<string, string>,
  defaultEncoding: string = FALLBACK_SELECTION,
): string {
  const manual = encodingSel[item.path];
  if (manual !== undefined) return manual;
  if (item.codepage === null) return defaultEncoding;
  return DETECTED_SELECTION[normalizeCodepage(item.codepage)] ?? FALLBACK_SELECTION;
}

/**
 * デコードプレビューの復号に用いるコードページ。手動指定があればその charset、なければ検出値。
 * 検出に失敗した資産(codepage=null)は設定の既定文字コードの charset を用い、それも手動指定で
 * なければ null を返して readSourceText が復号非対応として扱う。
 */
export function previewCodepage(
  item: AssetInventoryItem,
  encodingSel: Record<string, string>,
  defaultEncoding?: string,
): string | null {
  const selection = encodingSel[item.path] ?? (item.codepage === null ? defaultEncoding : undefined);
  const manual = selection === undefined ? undefined : MANUAL_CHARSET[selection];
  return manual ?? item.codepage;
}

/**
 * encodingSel を scan の codepageOverrides(相対パス → charset)へ変換する。自動判定は含めない。
 *
 * 既定文字コード(設定画面)はここへ現れない。engine の scan に既定コードページのオプションが
 * 無いため、既定値の反映先は GUI の初期値(選択欄とデコードプレビュー)までとし、engine へは
 * 資産ごとの手動指定だけを渡す。
 */
export function toCodepageOverrides(encodingSel: Record<string, string>): Record<string, string> {
  const overrides: Record<string, string> = {};
  for (const [path, selection] of Object.entries(encodingSel)) {
    const charset = MANUAL_CHARSET[selection];
    if (charset !== undefined) overrides[path] = charset;
  }
  return overrides;
}

/** 解析状態の色調(list/detail の文字色トークン選択に使う)。 */
export type AssetStatusTone = "muted" | "info" | "success" | "error";

export interface AssetStatus {
  readonly label: string;
  readonly tone: AssetStatusTone;
}

/**
 * 資産の解析状態(design の status 導出)。running は「解析中…」、empty は「—」。
 * results/error では復号失敗と構文解析失敗を区別する(裁定 A8)。scan が SOURCE へ紐づけて記録する
 * finding は復号失敗と構文解析失敗の 2 種だけなので、codepage が null でない資産の findingCount が
 * 構文解析失敗の直接の証拠になる(design:908 の PARSEF に対応)。復号失敗も finding を 1 件持つため、
 * codepage の判定を先に行う。
 */
export function analysisStatus(item: AssetInventoryItem, mode: ScreenMode): AssetStatus {
  if (mode === "running") return { label: "解析中…", tone: "info" };
  if (mode === "empty") return { label: "—", tone: "muted" };
  if (item.codepage === null) return { label: "✗ 復号失敗", tone: "error" };
  if (item.findingCount > 0) return { label: "✗ 構文解析失敗", tone: "error" };
  if (displayType(item.type) === "その他") return { label: "取込のみ", tone: "muted" };
  return { label: "✓ 解析済", tone: "success" };
}

/** ディレクトリ見出し配下の1資産行。 */
export interface AssetRow {
  readonly item: AssetInventoryItem;
  readonly name: string;
  readonly type: AssetDisplayType;
  readonly encoding: string;
  readonly status: AssetStatus;
  /** この資産に対する lint 指摘の件数。 */
  readonly findingCount: number;
  readonly selected: boolean;
}

/** ディレクトリ見出しと、その配下の資産行。 */
export interface AssetGroup {
  readonly dir: string;
  readonly count: number;
  readonly rows: readonly AssetRow[];
}

export interface BuildGroupsOptions {
  readonly search: string;
  readonly type: AssetTypeFilter;
  readonly encodingSel: Record<string, string>;
  readonly mode: ScreenMode;
  readonly selectedPath: string;
  /**
   * 相対パスごとの指摘件数(design fileFindCount)。供給源は lint の SARIF であり、
   * AssetInventoryItem.findingCount(scan 由来の復号・構文解析の失敗)とは別物である。
   */
  readonly findingCounts: Readonly<Record<string, number>>;
}

/** lint の指摘を相対パスごとに数える(資産一覧の指摘列の供給源)。 */
export function countFindingsByFile(findings: readonly SarifFinding[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const finding of findings) {
    counts[finding.file] = (counts[finding.file] ?? 0) + 1;
  }
  return counts;
}

/** 相対パスからディレクトリ部を取り出す。区切りが無ければ「（ルート）」。 */
export function directoryOf(path: string): string {
  const parts = path.split(/[\\/]/);
  if (parts.length <= 1) return "（ルート）";
  return parts.slice(0, -1).join("/");
}

/**
 * 資産一覧を「名前フィルタ + 種別チップ」で絞り込み、ディレクトリ単位へ集約した表示行を作る。
 * 入力は path 昇順(readInventory の並び)を前提に、ディレクトリの初出順を保つ。空グループは除く。
 */
export function buildAssetGroups(
  items: readonly AssetInventoryItem[],
  options: BuildGroupsOptions,
): AssetGroup[] {
  const order: string[] = [];
  const byDir = new Map<string, AssetRow[]>();

  for (const item of items) {
    const type = displayType(item.type);
    if (!matchesSearch(item.name, options.search)) continue;
    if (!matchesType(type, options.type)) continue;

    const dir = directoryOf(item.path);
    let rows = byDir.get(dir);
    if (rows === undefined) {
      rows = [];
      byDir.set(dir, rows);
      order.push(dir);
    }
    rows.push({
      item,
      name: item.name,
      type,
      encoding: effectiveEncoding(item, options.encodingSel),
      status: analysisStatus(item, options.mode),
      findingCount: options.findingCounts[item.path] ?? 0,
      selected: item.path === options.selectedPath,
    });
  }

  return order.map((dir) => {
    const rows = byDir.get(dir) ?? [];
    return { dir, count: rows.length, rows };
  });
}
