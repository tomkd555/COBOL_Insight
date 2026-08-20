import { RULE_CONFIG_VERSION, type RuleConfigFile } from "../../shared/engine-api";

/**
 * ルールの有効・無効の設定ファイル(rules-config.json)の読み書き。engine の --rule-config が読む形を
 * そのまま扱い、保存先は利用者定義ルールと同じ userData 直下に置く。
 *
 * 版数は engine が受理する値に固定する。engine は知らない版数を誤りとして扱い、終了コード 2 で
 * 止まるため、画面が書く側で必ずこの値を入れる。
 */

/** 設定ファイルの名前。userData 直下、user-rules.json の隣に置く。 */
export const RULE_CONFIG_FILE_NAME = "rules-config.json";

/** 設定ファイルの読み書きに使う fs の束ね。テストでは差し替える。 */
export interface RuleConfigFileSystem {
  readText(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/** 1件も無効にしていない状態。 */
export function emptyRuleConfig(): RuleConfigFile {
  return { version: RULE_CONFIG_VERSION, disabledRules: [] };
}

/** 読み込んだ値を型の揃った形へ整える。版数は画面が書く値へ必ず直す。 */
export function normalizeRuleConfig(value: unknown): RuleConfigFile {
  const source =
    value !== null && typeof value === "object" && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : {};
  const ids = source["disabledRules"];
  return {
    version: RULE_CONFIG_VERSION,
    disabledRules: Array.isArray(ids)
      ? ids.filter((id): id is string => typeof id === "string")
      : [],
  };
}

/**
 * 設定ファイルを読む。未作成・空・壊れている場合はいずれも空の設定を返す。
 * 読めない設定で画面を止めない(engine 側は同じファイルを誤りとして扱うが、その表示は
 * ルール一覧の担当であり、ここは読取だけを担う)。
 */
export async function readRuleConfig(
  fs: RuleConfigFileSystem,
  path: string,
): Promise<RuleConfigFile> {
  if (!(await fs.exists(path))) {
    return emptyRuleConfig();
  }
  try {
    const text = await fs.readText(path);
    if (text.trim() === "") {
      return emptyRuleConfig();
    }
    return normalizeRuleConfig(JSON.parse(text));
  } catch {
    return emptyRuleConfig();
  }
}

/** 設定ファイルを書く。人が読んで直せるよう字下げして書き、末尾に改行を置く。 */
export async function writeRuleConfig(
  fs: RuleConfigFileSystem,
  path: string,
  file: RuleConfigFile,
): Promise<void> {
  await fs.writeText(path, `${JSON.stringify(normalizeRuleConfig(file), null, 2)}\n`);
}

/**
 * 旧版の settings.json が持っていた disabledRules を rules-config.json へ移す。
 *
 * 無効にしたルールの置き場所は設定ファイルへ移り、engine も --rule-config だけを見る。移さないと、
 * 更新前に無効にしたルールが黙って復活する。移すのは rules-config.json がまだ無いときに限る
 * (設定ファイルが正であり、古い settings.json の値で上書きしない)。
 */
export async function migrateDisabledRules(
  fs: RuleConfigFileSystem,
  settingsPath: string,
  configPath: string,
): Promise<void> {
  if (await fs.exists(configPath)) {
    return;
  }
  if (!(await fs.exists(settingsPath))) {
    return;
  }
  let disabledRules: string[];
  try {
    const parsed: unknown = JSON.parse(await fs.readText(settingsPath));
    const file =
      parsed !== null && typeof parsed === "object" ? (parsed as Record<string, unknown>) : {};
    const settings =
      file["settings"] !== null && typeof file["settings"] === "object"
        ? (file["settings"] as Record<string, unknown>)
        : file;
    disabledRules = normalizeRuleConfig(settings).disabledRules;
  } catch {
    // 壊れた settings.json は移す値を持たない。次の保存で書き直る。
    return;
  }
  if (disabledRules.length === 0) {
    return;
  }
  await writeRuleConfig(fs, configPath, { version: RULE_CONFIG_VERSION, disabledRules });
}
