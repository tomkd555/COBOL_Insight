// jest-dom の matcher(toBeInTheDocument・toHaveAttribute 等)を全テストへ登録する。
import "@testing-library/jest-dom/vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { parseRuleCatalog } from "./src/shared/ruleCatalog";
import { setRuleCatalog } from "./src/renderer/src/data/ruleCatalog";

/**
 * ルールカタログを全テストへ取り込む。実行時は engine の `rules` サブコマンドが供給するが、
 * テストは engine を起動しないため、その出力を写した fixture を注入する。
 *
 * ルールを増減したとき、または説明を直したときは、次で fixture を作り直す。
 *   src\engine\cli\build\install\cli\bin\cli.bat rules --json > <この fixture>
 */
const fixture = readFileSync(
  join(__dirname, "src/renderer/src/data/__fixtures__/rules.json"),
  "utf-8",
);
setRuleCatalog(parseRuleCatalog(JSON.parse(fixture) as Record<string, unknown>).rules);
