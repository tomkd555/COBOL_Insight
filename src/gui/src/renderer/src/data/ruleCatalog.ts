/**
 * ルールカタログ。ルール名・カテゴリ・重大度・説明の単一の正は engine 側の RuleDoc であり、
 * 画面は起動時に `rules` サブコマンドで受け取った一覧をここへ取り込んで引く。
 *
 * 画面側に一覧を書き写さないのは、ルールを増減したときに engine と画面で食い違わせないためである。
 * 利用者定義ルールも同じ経路で載るため、画面は組み込みと利用者定義を同じ形で扱える。
 *
 * SARIF の level(error/warning/note)は3段だが、画面表示の重大度は4段(高/中/低/警告)で
 * ルール固有の属性である。したがって重大度は SARIF の level ではなく、このカタログを引いて決める。
 */

import type { RuleCatalogEntry } from "../../../shared/engine-api";
import type { Severity } from "../components/severity";

export interface RuleInfo {
  /** ルール ID(組み込みは R001〜R031・S001〜S006、利用者定義は U で始まる)。 */
  readonly id: string;
  /** ルール名称(画面の一覧・フィルタで表示)。 */
  readonly name: string;
  /** カテゴリ(設定画面のグループ化に用いる)。 */
  readonly category: string;
  /** 画面表示の重大度(高/中/低/警告)。 */
  readonly severity: Severity;
  /** 修正案 diff を生成できるルールか。engine の FixProducer 実装があるルールだけ true。 */
  readonly hasFix: boolean;
  /** 組み込みか、利用者が定義したものか。 */
  readonly source: "builtin" | "user";
  /** 何を検出するか。 */
  readonly summary: string;
  /** なぜ問題か。 */
  readonly rationale: string;
  /** 検出条件。対象外の扱いも含む。 */
  readonly detection: string;
  /** どう直すか。 */
  readonly remedy: string;
  /** 該当する例。書けないルールでは空文字。 */
  readonly badExample: string;
  /** 直した例。書けないルールでは空文字。 */
  readonly goodExample: string;
}

/** engine の Severity 語彙から画面の重大度への対応。 */
const SEVERITY_BY_ENGINE_NAME: Readonly<Record<string, Severity>> = {
  HIGH: "high",
  MEDIUM: "medium",
  LOW: "low",
  ADVISORY: "warning",
};

let catalog: Record<string, RuleInfo> = {};
let order: string[] = [];
let loaded = false;

/**
 * engine が返した一覧を取り込む。並びは engine が返した順(id 昇順)をそのまま保ち、
 * 設定画面のカテゴリ別のまとめもこの並びを基準にする。
 */
export function setRuleCatalog(entries: readonly RuleCatalogEntry[]): void {
  const next: Record<string, RuleInfo> = {};
  const ids: string[] = [];
  for (const entry of entries) {
    next[entry.id] = {
      id: entry.id,
      name: entry.name,
      category: entry.category,
      severity: SEVERITY_BY_ENGINE_NAME[entry.severity] ?? "medium",
      hasFix: entry.hasFix,
      source: entry.source,
      summary: entry.summary,
      rationale: entry.rationale,
      detection: entry.detection,
      remedy: entry.remedy,
      badExample: entry.badExample,
      goodExample: entry.goodExample,
    };
    ids.push(entry.id);
  }
  catalog = next;
  order = ids;
  loaded = true;
}

/** 取り込み済みか。未取得のうちは設定画面が一覧の代わりに読み込み中を示す。 */
export function isRuleCatalogLoaded(): boolean {
  return loaded;
}

/** 取り込んだルール ID(engine が返した並び)。 */
export function ruleIds(): readonly string[] {
  return order;
}

/** 取り込んだルールの件数。 */
export function ruleCount(): number {
  return order.length;
}

/** 取り込んだルール一覧(engine が返した並び)。 */
export function allRules(): readonly RuleInfo[] {
  return order.map((id) => catalog[id]);
}

/**
 * engine が検出ルール以外に出す解析エラーの ID と名称。lint・scan は構文解析の失敗
 * (Finding.PARSE_FAILURE_RULE_ID)と復号の失敗(LintRunner・ScanRunner の decode-failure)を
 * 別の ID で記録するため、名称も区別する。
 */
const ANALYSIS_ERROR_NAMES: Readonly<Record<string, string>> = {
  "parse-failure": "構文解析失敗",
  "decode-failure": "文字コードの復号失敗",
};

/**
 * カタログにない ID へのフォールバック。見落としを防ぐため重大度は高とし、名称は解析エラーの ID
 * だけをその内容で名付ける。engine が出さない ID を「構文解析失敗」として示さない。
 */
function fallbackRule(id: string): RuleInfo {
  const analysisError = ANALYSIS_ERROR_NAMES[id];
  return {
    id,
    name: analysisError ?? "未登録のルール",
    category: "解析エラー",
    severity: "high",
    hasFix: false,
    source: "builtin",
    summary: analysisError === undefined
      ? "この ID のルールはカタログに無い。"
      : "解析そのものが失敗したことを示す。ルールによる検出ではない。",
    rationale: "",
    detection: "",
    remedy: analysisError === undefined
      ? ""
      : "対象ファイルの文字コード指定と、コピー句の探索パスを確かめる。",
    badExample: "",
    goodExample: "",
  };
}

/** ルール ID からメタ情報を引く。未知の ID はフォールバックを返す。 */
export function ruleOf(id: string): RuleInfo {
  return catalog[id] ?? fallbackRule(id);
}
