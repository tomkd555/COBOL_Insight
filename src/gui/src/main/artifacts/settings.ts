import type { AppSettings, AppSettingsFile } from "../../shared/appSettings";
import {
  APP_SETTINGS_VERSION,
  emptyAppSettings,
  normalizeAppSettings,
} from "../../shared/appSettings";
import { readJsonFile, type JsonFileSystem } from "./jsonFile";

/**
 * 画面の設定の読み書き。保存先は userData 直下の settings.json であり、engine は触らない。
 *
 * **読めない設定で起動を止めない**。ファイルが無い・空・壊れている場合はいずれも空の設定を
 * 返し、画面の初期値で始める。設定は解析の結果を左右しない補助的な値であり、
 * これを理由にアプリが立ち上がらない方が損害が大きい。
 */

/** 設定ファイルの名前。userData 直下へ置く。 */
export const SETTINGS_FILE_NAME = "settings.json";

/** 設定ファイルの読み書きに使う fs の束ね。テストでは差し替える。 */
export type SettingsFileSystem = JsonFileSystem;

/** 設定を読む。読めない場合は空の設定を返す。 */
export async function readSettings(
  fs: SettingsFileSystem,
  path: string,
): Promise<AppSettings> {
  return readJsonFile(fs, path, normalizeAppSettings, emptyAppSettings);
}

/**
 * 設定を書く。人が読んで直せるよう字下げして書き、末尾に改行を置く。
 *
 * 旧版が置いていた disabledRules は、移行が rules-config.json を書き終えるまで持ち越す。画面は
 * この値を持たないため、保存のたびに現在のファイルから写す。落としてしまうと、移行がまだ済んで
 * いない環境で、無効にしたルールが黙って復活する。
 */
export async function writeSettings(
  fs: SettingsFileSystem,
  path: string,
  settings: AppSettings,
): Promise<void> {
  const stored = await readSettings(fs, path);
  const normalized = normalizeAppSettings(settings);
  const file: AppSettingsFile = {
    version: APP_SETTINGS_VERSION,
    settings:
      stored.disabledRules === undefined
        ? normalized
        : { ...normalized, disabledRules: stored.disabledRules },
  };
  await fs.writeText(path, `${JSON.stringify(file, null, 2)}\n`);
}
