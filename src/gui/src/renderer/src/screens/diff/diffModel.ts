/**
 * diff(fix)画面の純ロジック。engine の `fix preview` と `fix apply` が出したサマリ JSON と、lint の
 * 指摘(SARIF)を組み合わせて、修正案の一覧・採用/棄却の集合・書出先パスを導く。
 *
 * 差分そのものは GUI で計算しない。左右2ペインには readFixResult が返す原本テキストと
 * 修正後テキストの対をそのまま渡し、コピー句の修正(engine が修正後ソースを書き出さないもの)は
 * `fix preview` が標準出力へ書いた unified diff を切り出して素の文字列として示す。
 *
 * 修正案を持つルールは engine の FixProducer 実装と一致させる(一覧は FIX_RULE_IDS が唯一の正)。
 */

import type {
  FixCopybookImpact,
  FixSummaryInfo,
  SarifFinding,
} from "../../../../shared/engine-api";
import { parseFixSummary } from "../../../../shared/fixSummary";
import { ruleOf, type RuleCatalogIndex } from "../../data/ruleCatalog";
import type { AnalysisMode, FixDecision } from "../../state/projectStore";

/** 修正案の判定。採否は利用者の判断であり、置き場所は projectStore である。 */
export type { FixDecision };

/** 修正案を生成できるルール ID。engine の FixProducer 実装と一致させる。 */
export const FIX_RULE_IDS: readonly string[] = ["R004", "R017", "R018", "R021"];

/** --db 未指定時に engine が使う既定のプロジェクトファイル名。 */
const DEFAULT_DB_FILE = "cobol-insight.db";

/** 修正案カードに添える、そのファイルの修正対象となった指摘1件。 */
export interface FixFinding {
  readonly ruleId: string;
  readonly ruleName: string;
  readonly line: number;
  readonly message: string;
}

/**
 * 修正案1件。engine は修正をファイル単位で束ねて出す(FileFix)ため、カードもファイル単位である。
 * 同一ファイルに複数の修正が入る場合、findings がその内訳になる。
 */
export interface FixCandidate {
  readonly relPath: string;
  /** ファイル名(相対パスの末尾要素)。 */
  readonly name: string;
  /** コピー句の修正か。真のとき engine は原本を書き換えず、修正後ソースも書き出さない。 */
  readonly copybook: boolean;
  /** コピー句を取り込むプログラム一覧。copybook が真のときだけ非空になる。 */
  readonly importers: readonly string[];
  /** このファイルで修正案の対象となった指摘(FIX_RULE_IDS に含まれるもの)。行の昇順。 */
  readonly findings: readonly FixFinding[];
}

/**
 * `fix preview` の起動と、修正後ソースの実体化(検証用の書き出し)の状態。
 * summary は preview のサマリ、stdout は preview が書いた unified diff 本体である。
 */
export type FixState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | {
      readonly status: "ready";
      readonly summary: FixSummaryInfo;
      readonly stdout: string;
      /** 検証用の書き出しで再パースに失敗した件数。実体化しなかったときは null。 */
      readonly reparseFailures: number | null;
      /** 修正後ソースを実体化した作業フォルダ。実体化しなかったときは null。 */
      readonly materializedDir: string | null;
    }
  | { readonly status: "error"; readonly message: string };

/** 左右2ペインへ渡す差分の取得状態。 */
export type DiffTextState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly originalText: string; readonly fixedText: string }
  /** 修正後ソースが存在しない(コピー句など)。unified diff を素で示す。 */
  | { readonly status: "unified"; readonly lines: readonly string[]; readonly reason: string }
  | { readonly status: "error"; readonly message: string };

/** 「適用(書き出し)」の結果。engine が書いたファイルと検証結果を利用者へ示す。 */
export interface FixApplyOutcome {
  readonly outDir: string;
  readonly written: readonly string[];
  readonly copybookFixes: readonly FixCopybookImpact[];
  readonly reparseFailures: number;
}

/** 修正案の書出先。原本は書き換えず、いずれもプロジェクトファイルと同じ場所の直下へ置く。 */
export interface FixOutputPaths {
  /** 差分表示のために修正後ソースを実体化する作業フォルダ。 */
  readonly previewDir: string;
  /** 「適用(書き出し)」の正式な出力先。 */
  readonly applyDir: string;
}

/** diff 画面の表示状態。4状態に、資産フォルダ未選択を加えて導く。 */
export type DiffView =
  | { readonly kind: "empty" }
  | { readonly kind: "no-project" }
  | { readonly kind: "running" }
  | { readonly kind: "error"; readonly message: string }
  | { readonly kind: "results" };

/** engine のサマリ JSON から修正案の情報を取り出す。main 側と共通の正規化(shared/fixSummary)を使う。 */
export const readFixSummary = parseFixSummary;

/**
 * preview の対象ファイル一覧とコピー句の影響範囲、lint の指摘から修正案カードの並びを組む。
 * 並びは engine が出したファイル順(決定論的)を保つ。コピー句だけが影響範囲に載る場合も
 * 一覧から落とさない。
 */
export function buildFixCandidates(
  catalog: RuleCatalogIndex,
  summary: FixSummaryInfo,
  findings: readonly SarifFinding[],
): FixCandidate[] {
  const importersByPath = new Map(
    summary.copybookFixes.map((impact) => [impact.copybook, impact.importers]),
  );
  const paths: string[] = [...summary.files];
  for (const impact of summary.copybookFixes) {
    if (!paths.includes(impact.copybook)) {
      paths.push(impact.copybook);
    }
  }
  return paths.map((relPath) => {
    const importers = importersByPath.get(relPath);
    return {
      relPath,
      name: fileNameOf(relPath),
      copybook: importers !== undefined,
      importers: importers ?? [],
      findings: fixFindingsOf(catalog, relPath, findings),
    };
  });
}

/** 修正案の対象となった指摘だけを、そのファイル分だけ行の昇順で取り出す。 */
function fixFindingsOf(
  catalog: RuleCatalogIndex,
  relPath: string,
  findings: readonly SarifFinding[],
): FixFinding[] {
  return findings
    .filter((finding) => finding.file === relPath && FIX_RULE_IDS.includes(finding.ruleId))
    .map((finding) => ({
      ruleId: finding.ruleId,
      ruleName: ruleOf(catalog, finding.ruleId).name,
      line: finding.startLine,
      message: finding.message,
    }))
    .sort((a, b) => a.line - b.line || a.ruleId.localeCompare(b.ruleId));
}

/** 修正案を持つルール ID を区切り文字でつないだ一覧。FIX_RULE_IDS が増減しても文言はそれに追随する。 */
export function fixRuleIdLabel(separator: string = "・"): string {
  return FIX_RULE_IDS.join(separator);
}

/** 修正案を持つルールを「ID（名称）」の形で列挙し、読点でつなぐ。 */
export function fixRuleDescriptionLabel(catalog: RuleCatalogIndex): string {
  return FIX_RULE_IDS.map((id) => `${id}（${ruleOf(catalog, id).name}）`).join("・");
}

/** 一覧見出しの件数表示。修正案を持つルールを併記する。 */
export function fixCountLabel(candidates: readonly FixCandidate[]): string {
  return `${candidates.length} 件（${fixRuleIdLabel(" / ")}）`;
}

/** カードと詳細見出しに出すルールの要約。指摘が取れていないときはファイル名だけを示す。 */
export function candidateRuleSummary(catalog: RuleCatalogIndex, candidate: FixCandidate): string {
  const ids = [...new Set(candidate.findings.map((finding) => finding.ruleId))];
  if (ids.length === 0) {
    return "修正案";
  }
  if (ids.length === 1) {
    return `${ids[0]} ${ruleOf(catalog, ids[0]).name}`;
  }
  return `${ids.join("・")}（${ids.length} ルール）`;
}

/** カードに出す位置。先頭の指摘行を添え、指摘が取れていないときは相対パスだけを示す。 */
export function candidateLocation(candidate: FixCandidate): string {
  const first = candidate.findings[0];
  return first === undefined ? candidate.relPath : `${candidate.relPath}:${first.line}`;
}

/** 判定の表示文言。未判定は判断がまだ無い状態として、採用・棄却と区別して示す。 */
export function decisionLabel(decision: FixDecision | undefined): string {
  if (decision === "adopted") return "✓ 採用済";
  if (decision === "rejected") return "✗ 棄却済";
  return "未判定";
}

/** 判定に対応する BEM 修飾子の語幹。 */
export function decisionModifier(decision: FixDecision | undefined): string {
  return decision ?? "pending";
}

/** 判定を1件設定した新しい集合を返す。同じ判定を再度指定した場合は未判定へ戻す。 */
export function setDecision(
  decisions: Readonly<Record<string, FixDecision>>,
  relPath: string,
  decision: FixDecision,
): Record<string, FixDecision> {
  const next: Record<string, FixDecision> = { ...decisions };
  if (next[relPath] === decision) {
    delete next[relPath];
  } else {
    next[relPath] = decision;
  }
  return next;
}

/** 判定の内訳。一覧に現れない判定(再解析で消えた修正案)は数えない。 */
export interface DecisionCounts {
  readonly adopted: number;
  readonly rejected: number;
  readonly pending: number;
}

export function decisionCounts(
  candidates: readonly FixCandidate[],
  decisions: Readonly<Record<string, FixDecision>>,
): DecisionCounts {
  let adopted = 0;
  let rejected = 0;
  for (const candidate of candidates) {
    const decision = decisions[candidate.relPath];
    if (decision === "adopted") adopted += 1;
    else if (decision === "rejected") rejected += 1;
  }
  return { adopted, rejected, pending: candidates.length - adopted - rejected };
}

/** 採用した修正案の相対パス。一覧の順序を保つ。 */
export function adoptedPaths(
  candidates: readonly FixCandidate[],
  decisions: Readonly<Record<string, FixDecision>>,
): string[] {
  return candidates
    .filter((candidate) => decisions[candidate.relPath] === "adopted")
    .map((candidate) => candidate.relPath);
}

/** 選択中の修正案を解決する。選択が一覧から消えていれば先頭を採り、一覧が空なら null を返す。 */
export function resolveSelection(
  candidates: readonly FixCandidate[],
  selected: string,
): FixCandidate | null {
  return candidates.find((candidate) => candidate.relPath === selected) ?? candidates[0] ?? null;
}

/** 修正案の書出先を、プロジェクトファイルの置き場所から導く。 */
export function fixOutputPaths(dbPath: string | null): FixOutputPaths {
  const db = dbPath ?? DEFAULT_DB_FILE;
  const separator = Math.max(db.lastIndexOf("\\"), db.lastIndexOf("/"));
  const dir = separator < 0 ? "" : db.slice(0, separator + 1);
  return { previewDir: `${dir}fix-preview`, applyDir: `${dir}fix` };
}

/** 基準側の区切り文字にそろえてパスを連結する。相対パス側の区切りも基準側へ写す。 */
export function joinPath(base: string, relPath: string): string {
  const separator = base.includes("\\") ? "\\" : "/";
  const trimmed = base.endsWith("\\") || base.endsWith("/") ? base.slice(0, -1) : base;
  return `${trimmed}${separator}${relPath.replace(/[\\/]/g, separator)}`;
}

/**
 * preview が標準出力へ書いた unified diff から、1ファイル分の行を切り出す。差分の再計算はせず、
 * engine が書いた行をそのまま返す。該当ファイルの差分が無ければ空配列を返す。
 */
export function extractUnifiedDiff(stdout: string, relPath: string): string[] {
  const lines = stdout.split(/\r?\n/);
  const start = lines.indexOf(`--- a/${relPath}`);
  if (start < 0) {
    return [];
  }
  const body: string[] = [];
  for (let index = start; index < lines.length; index += 1) {
    const line = lines[index];
    // 次のファイルの見出し・コピー句の注記・末尾のサマリ JSON で1ファイル分を打ち切る。
    if (index > start && (line.startsWith("--- a/") || line.startsWith("# ") || line.startsWith("{"))) {
      break;
    }
    body.push(line);
  }
  // 末尾の空行は差分の一部ではないため落とす。
  while (body.length > 0 && body[body.length - 1] === "") {
    body.pop();
  }
  return body;
}

/** 再パース検証の失敗を示す警告文。失敗が無ければ null。 */
export function reparseWarning(reparseFailures: number | null): string | null {
  if (reparseFailures === null || reparseFailures === 0) {
    return null;
  }
  return `修正後ソースの再構文解析で ${reparseFailures} 件が検証に失敗しました。失敗した修正は内容を確かめて棄却するか、手作業で直してください。`;
}

/** 解析段の失敗(復号・構文解析)を示す警告文。失敗が無ければ null。 */
export function analysisWarning(summary: FixSummaryInfo): string | null {
  if (summary.analysisErrors === 0) {
    return null;
  }
  return `解析で ${summary.analysisErrors} 件のエラーがあります。修正案は解析できた資産の範囲で生成しています。`;
}

/**
 * 再パース検証の警告と解析段の警告を1本にまとめる。両方が出ると帯が2段になり、そのぶんコード面の
 * 高さが減る。どちらも「修正案の信頼度に関わる注意」であり、読む側にとって段を分ける意味が無い。
 * どちらも無ければ null を返し、帯そのものを出さない。
 */
export function mergeWarnings(
  reparse: string | null,
  analysis: string | null,
): string | null {
  const parts = [reparse, analysis].filter((part): part is string => part !== null);
  return parts.length === 0 ? null : parts.join(" ");
}

/**
 * 「適用(書き出し)」の前に示す注意文。engine の `fix apply` は修正案を選んで書き出す機能を持たず
 * 全件を書き出すため、棄却した修正案も出力先に現れる。その事実を書き出し前に明示する。
 */
export function applyCaution(counts: DecisionCounts): string | null {
  if (counts.rejected === 0) {
    return null;
  }
  return `棄却した ${counts.rejected} 件も書き出しに含まれます。書き出しでは修正案を選べないため、取り込むときに判定を確かめてください。`;
}

/** 書き出し後の結果文言。 */
export function applyNotice(outcome: FixApplyOutcome): string {
  const held =
    outcome.copybookFixes.length === 0
      ? ""
      : ` コピー句 ${outcome.copybookFixes.length} 件は原本を書き換えないため提示に留めています。`;
  const failed =
    outcome.reparseFailures === 0 ? "" : ` 再構文解析の失敗 ${outcome.reparseFailures} 件。`;
  return `${outcome.outDir} へ ${outcome.written.length} 件を書き出しました（原本は変更していません）。${held}${failed}`;
}

/** diff 画面の4状態を、解析ライフサイクルと fix の取得状態から導く。 */
export function deriveDiffView(
  mode: AnalysisMode,
  inputDir: string | null,
  fix: FixState,
): DiffView {
  if (mode === "empty") {
    return { kind: "empty" };
  }
  if (mode === "running") {
    return { kind: "running" };
  }
  if (inputDir === null) {
    return { kind: "no-project" };
  }
  if (fix.status === "error") {
    return { kind: "error", message: fix.message };
  }
  if (fix.status === "ready") {
    return { kind: "results" };
  }
  return { kind: "running" };
}

function fileNameOf(relPath: string): string {
  const separator = Math.max(relPath.lastIndexOf("\\"), relPath.lastIndexOf("/"));
  return separator < 0 ? relPath : relPath.slice(separator + 1);
}
