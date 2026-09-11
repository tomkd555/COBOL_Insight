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

/**
 * Writes the settings, merged shallowly over what is already stored: a caller sends only the keys
 * it owns, and every other key (another screen's, or a key it does not know about) survives.
 */
export async function writeSettings(
  fs: SettingsFileSystem,
  path: string,
  settings: Partial<AppSettings>,
): Promise<void> {
  const existing = await readSettings(fs, path);
  const file: AppSettingsFile = {
    version: APP_SETTINGS_VERSION,
    settings: normalizeAppSettings({ ...existing, ...settings }),
  };
  await writeJsonFile(fs, path, file);
}
