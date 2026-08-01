import type { AppSettings } from "../../shared/appSettings";
import {
  APP_SETTINGS_VERSION,
  emptyAppSettings,
  normalizeAppSettings,
} from "../../shared/appSettings";

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
export interface SettingsFileSystem {
  readText(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/** 設定を読む。読めない場合は空の設定を返す。 */
export async function readSettings(
  fs: SettingsFileSystem,
  path: string,
): Promise<AppSettings> {
  if (!(await fs.exists(path))) {
    return emptyAppSettings();
  }
  try {
    const text = await fs.readText(path);
    if (text.trim() === "") {
      return emptyAppSettings();
    }
    return normalizeAppSettings(JSON.parse(text));
  } catch {
    // 壊れた設定ファイルは無かったものとして扱う。次の保存で書き直る。
    return emptyAppSettings();
  }
}

/** 設定を書く。人が読んで直せるよう字下げして書き、末尾に改行を置く。 */
export async function writeSettings(
  fs: SettingsFileSystem,
  path: string,
  settings: AppSettings,
): Promise<void> {
  const file = { version: APP_SETTINGS_VERSION, settings: normalizeAppSettings(settings) };
  await fs.writeText(path, `${JSON.stringify(file, null, 2)}\n`);
}
