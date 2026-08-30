import type { AppSettings, AppSettingsFile } from "../../shared/settings";
import {
  APP_SETTINGS_VERSION,
  emptyAppSettings,
  normalizeAppSettings,
} from "../../shared/settings";
import { readJsonFile, writeJsonFile, type JsonFileSystem } from "./jsonFile";

/**
 * The GUI settings, stored as settings.json directly under userData. The engine never touches it.
 */

export const SETTINGS_FILE_NAME = "settings.json";

export type SettingsFileSystem = JsonFileSystem;

/** Reads the settings; an unreadable file yields the empty settings. */
export async function readSettings(fs: SettingsFileSystem, path: string): Promise<AppSettings> {
  return readJsonFile(fs, path, normalizeAppSettings, emptyAppSettings);
}

/** Writes the settings. */
export async function writeSettings(
  fs: SettingsFileSystem,
  path: string,
  settings: AppSettings,
): Promise<void> {
  const file: AppSettingsFile = {
    version: APP_SETTINGS_VERSION,
    settings: normalizeAppSettings(settings),
  };
  await writeJsonFile(fs, path, file);
}
