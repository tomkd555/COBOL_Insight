import type { UserRulesFile } from "../../shared/engine-api";
import { emptyUserRules, normalizeUserRulesFile } from "../../shared/userRules";

/**
 * 利用者定義ルールの定義ファイル(user-rules.json)の読み書き。engine の UserRuleLoader が読む
 * 形式をそのまま扱う。定義の正規化と検証は shared/userRules が持ち、ここは入出力だけを担う。
 */

/** 定義ファイルの読み書きに使う fs の束ね。テストでは差し替える。 */
export interface UserRuleFileSystem {
  readText(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/** 定義ファイルを読む。未作成のときは空の定義を返す(利用者がまだ1件も作っていない状態)。 */
export async function readUserRules(
  fs: UserRuleFileSystem,
  path: string,
): Promise<UserRulesFile> {
  if (!(await fs.exists(path))) {
    return emptyUserRules();
  }
  const text = await fs.readText(path);
  if (text.trim() === "") {
    return emptyUserRules();
  }
  return normalizeUserRulesFile(JSON.parse(text));
}

/** 定義ファイルを書く。人が読んで直せるよう字下げして書き、末尾に改行を置く。 */
export async function writeUserRules(
  fs: UserRuleFileSystem,
  path: string,
  file: UserRulesFile,
): Promise<void> {
  const normalized = normalizeUserRulesFile(file);
  await fs.writeText(path, `${JSON.stringify(normalized, null, 2)}\n`);
}
