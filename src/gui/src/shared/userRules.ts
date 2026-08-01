/**
 * 利用者定義ルールの定義の正規化と検証。main(ファイルの読み書き)と renderer(作成画面)の双方が
 * 使うため shared へ置く。
 *
 * 定義を最終的に受理するかどうかを決めるのは engine の UserRuleLoader である。ここでの検証は、
 * 誤りを保存する前に画面で示すためのものであり、engine 側の判定を置き換えるものではない。
 * 正規表現の構文も、engine は Java、画面は JavaScript で解釈するため完全には一致しない。
 */

import type { UserRuleDefinition, UserRulesFile } from "./engine-api";

/** 定義ファイルの版数。engine の UserRuleLoader.SUPPORTED_VERSION と揃える。 */
export const USER_RULES_VERSION = 1;

/** 走査できる資産の種別。engine の UserRuleTarget と揃える。 */
export const USER_RULE_TARGETS: readonly string[] = ["COBOL", "COPYBOOK", "BMS"];

/** 重大度の語彙。engine の Severity と揃える。 */
export const USER_RULE_SEVERITIES: readonly string[] = ["HIGH", "MEDIUM", "LOW", "ADVISORY"];

/** ID の形。engine が U 始まりだけを受理するため、画面でも同じ形を求める。 */
const ID_FORMAT = /^U[0-9A-Za-z_-]{1,15}$/;

/** 空の定義。ファイルが未作成のときに用いる。 */
export function emptyUserRules(): UserRulesFile {
  return { version: USER_RULES_VERSION, rules: [] };
}

/**
 * 1件の定義を、欄が揃った形へ整える。手書きの定義や画面の入力途中で欄が欠けていても、
 * engine が読める形にする。id・name・pattern・message は必須であり、欠けていれば例外にする。
 */
export function normalizeUserRule(value: unknown): UserRuleDefinition {
  const source = asObject(value);
  return {
    id: requireText(source, "id"),
    name: requireText(source, "name"),
    category: optionalText(source, "category", "利用者定義"),
    severity: optionalText(source, "severity", "MEDIUM").toUpperCase(),
    targets: normalizeTargets(source["targets"]),
    pattern: requireText(source, "pattern"),
    excludePattern: optionalText(source, "excludePattern", ""),
    ignoreCase: source["ignoreCase"] === true,
    wholeLine: source["wholeLine"] === true,
    message: requireText(source, "message"),
    rationale: optionalText(source, "rationale", ""),
    remedy: optionalText(source, "remedy", ""),
  };
}

/** 定義ファイル全体を整える。読めない1件で全部を捨てないよう、その要素だけを外す。 */
export function normalizeUserRulesFile(value: unknown): UserRulesFile {
  const source = asObject(value);
  const rules: UserRuleDefinition[] = [];
  for (const element of Array.isArray(source["rules"]) ? source["rules"] : []) {
    try {
      rules.push(normalizeUserRule(element));
    } catch {
      // 欄の欠けた定義は画面へ出さない。engine 側も同じ定義を読み飛ばして誤りを報告する。
    }
  }
  return { version: USER_RULES_VERSION, rules };
}

/** 次に使える ID。U001 から順に、まだ使われていない最初の番号を返す。 */
export function nextUserRuleId(rules: readonly UserRuleDefinition[]): string {
  const used = new Set(rules.map((rule) => rule.id.toUpperCase()));
  for (let number = 1; number <= 999; number++) {
    const candidate = `U${String(number).padStart(3, "0")}`;
    if (!used.has(candidate)) {
      return candidate;
    }
  }
  throw new Error("利用者定義ルールの ID が上限（U999）まで埋まっている");
}

/** 作成画面の初期値。ID は既存と重ならない番号を自動で採る。 */
export function newUserRuleDraft(existing: readonly UserRuleDefinition[]): UserRuleDefinition {
  return {
    id: nextUserRuleId(existing),
    name: "",
    category: "利用者定義",
    severity: "MEDIUM",
    targets: ["COBOL"],
    pattern: "",
    excludePattern: "",
    ignoreCase: false,
    wholeLine: false,
    message: "",
    rationale: "",
    remedy: "",
  };
}

/**
 * 保存する前の検査。誤りの説明を並べて返し、空であれば保存できる。
 * editingIndex は編集中の定義の位置(新規追加は null)で、自分自身との ID 重複を除くために使う。
 */
export function validateUserRule(
  draft: UserRuleDefinition,
  existing: readonly UserRuleDefinition[],
  editingIndex: number | null,
): string[] {
  const errors: string[] = [];
  if (!ID_FORMAT.test(draft.id)) {
    errors.push("ID は U で始まり、英数字・ハイフン・下線が 1〜15 文字続く形にする（例: U001）。");
  }
  const duplicated = existing.some(
    (rule, index) => index !== editingIndex && rule.id.toUpperCase() === draft.id.toUpperCase(),
  );
  if (duplicated) {
    errors.push(`ID ${draft.id} は既に使われている。`);
  }
  if (draft.name.trim() === "") {
    errors.push("名称を入力する。");
  }
  if (draft.message.trim() === "") {
    errors.push("指摘のメッセージを入力する。");
  }
  if (draft.targets.length === 0) {
    errors.push("走査する資産の種別を1つ以上選ぶ。");
  }
  if (!USER_RULE_SEVERITIES.includes(draft.severity)) {
    errors.push("重大度は 高・中・低・警告 のいずれかにする。");
  }
  errors.push(...patternErrorsOf(draft.pattern, "正規表現"));
  if (draft.excludePattern.trim() !== "") {
    errors.push(...patternErrorsOf(draft.excludePattern, "除外する正規表現"));
  }
  return errors;
}

function patternErrorsOf(pattern: string, label: string): string[] {
  if (pattern.trim() === "") {
    return [`${label}を入力する。`];
  }
  try {
    new RegExp(pattern);
    return [];
  } catch (error) {
    return [`${label}を解釈できない: ${error instanceof Error ? error.message : String(error)}`];
  }
}

function normalizeTargets(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return ["COBOL"];
  }
  const targets = value
    .map((element) => String(element).toUpperCase())
    .filter((element) => USER_RULE_TARGETS.includes(element));
  return targets.length === 0 ? ["COBOL"] : [...new Set(targets)];
}

function asObject(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("利用者定義ルールの定義はオブジェクトで書く");
  }
  return value as Record<string, unknown>;
}

function requireText(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.trim() === "") {
    throw new Error(`利用者定義ルールの ${key} が無い、または空である`);
  }
  return value.trim();
}

function optionalText(source: Record<string, unknown>, key: string, fallback: string): string {
  const value = source[key];
  return typeof value === "string" && value.trim() !== "" ? value.trim() : fallback;
}
