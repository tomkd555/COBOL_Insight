import type { RulesFile } from "../../shared/rulesFile";
import { RULES_FILE_VERSION, emptyRulesFile, normalizeRulesFile } from "../../shared/rulesFile";
import { readJsonFile, writeJsonFile, type JsonFileSystem } from "./jsonFile";

/**
 * The one rule configuration file (rules.json under userData). The GUI writes it and the engine
 * reads it through --rules.
 */

export const RULES_FILE_NAME = "rules.json";

export type RulesFileSystem = JsonFileSystem;

/** Reads the rule file; an unreadable file yields an empty configuration. */
export async function readRulesFile(fs: RulesFileSystem, path: string): Promise<RulesFile> {
  return readJsonFile(fs, path, normalizeRulesFile, emptyRulesFile);
}

/** Writes the rule file, always stamping the version the engine expects. */
export async function writeRulesFile(
  fs: RulesFileSystem,
  path: string,
  file: RulesFile,
): Promise<void> {
  await writeJsonFile(fs, path, { ...normalizeRulesFile(file), version: RULES_FILE_VERSION });
}
