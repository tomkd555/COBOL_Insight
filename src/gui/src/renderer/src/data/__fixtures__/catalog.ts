/**
 * テストが使うルール一覧。実行時は engine の `rules` サブコマンドが供給するが、テストは engine を
 * 起動しないため、その出力をそのまま取り込んだ rules.json を索引へ組んで配る。
 *
 * ルールを増減したとき、または説明を直したときは、次で rules.json を作り直す。
 *   src\engine\cli\build\install\cli\bin\cli.bat rules --json > <この fixture>
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseRuleCatalog } from "../../../../shared/ruleCatalog";
import { buildRuleCatalog, type RuleCatalogIndex } from "../ruleCatalog";
import type { RuleCatalogEntry } from "../../../../shared/engine-api";

export const FIXTURE_RULE_ENTRIES: readonly RuleCatalogEntry[] = parseRuleCatalog(
  JSON.parse(readFileSync(join(__dirname, "rules.json"), "utf-8")) as Record<string, unknown>,
).rules;

export const FIXTURE_CATALOG: RuleCatalogIndex = buildRuleCatalog(FIXTURE_RULE_ENTRIES);
