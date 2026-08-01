import type { RuleCatalog, RuleCatalogEntry } from "./engine-api";

/**
 * engine の `rules --json` の出力を、画面が引くルールカタログへ写す。ルール名・カテゴリ・説明を
 * 画面側に置かず engine から受けるための入口であり、ここが唯一の変換点である。
 * main(IPC の応答を組む側)と renderer(テストで同じ形を組む側)の双方が使うため shared へ置く。
 *
 * 欄が欠けた要素は落とさず既定で補う。engine の版が画面より古く欄が足りない場合でも、
 * 一覧そのものは描けるようにするためである。id を持たない要素だけは引けないため外す。
 */
export function parseRuleCatalog(summary: Record<string, unknown> | null): RuleCatalog {
  if (summary === null) {
    throw new Error("engine の rules がルール一覧を返さなかった");
  }
  const rules: RuleCatalogEntry[] = [];
  for (const element of asArray(summary["rules"])) {
    const entry = toEntry(element);
    if (entry !== null) {
      rules.push(entry);
    }
  }
  return {
    rules,
    userRuleErrors: asArray(summary["userRuleErrors"]).map((error) => String(error)),
  };
}

function toEntry(value: unknown): RuleCatalogEntry | null {
  const id = asString(prop(value, "id"));
  if (id === undefined || id === "") {
    return null;
  }
  return {
    id,
    name: asString(prop(value, "name")) ?? "",
    category: asString(prop(value, "category")) ?? "",
    severity: asString(prop(value, "severity")) ?? "MEDIUM",
    phase: asString(prop(value, "phase")) ?? "SYNTAX",
    hasFix: prop(value, "hasFix") === true,
    source: prop(value, "source") === "user" ? "user" : "builtin",
    summary: asString(prop(value, "summary")) ?? "",
    rationale: asString(prop(value, "rationale")) ?? "",
    detection: asString(prop(value, "detection")) ?? "",
    remedy: asString(prop(value, "remedy")) ?? "",
    badExample: asString(prop(value, "badExample")) ?? "",
    goodExample: asString(prop(value, "goodExample")) ?? "",
  };
}

function prop(value: unknown, key: string): unknown {
  return value !== null && typeof value === "object"
    ? (value as Record<string, unknown>)[key]
    : undefined;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}
