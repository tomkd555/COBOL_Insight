/**
 * Reading and writing the JSON configuration files kept under userData (the settings and the rule
 * file).
 *
 * **A configuration file that cannot be read must not stop the application.** Absent, empty, corrupt
 * and unreadable all yield the empty value, and the screens start from their own defaults. What the
 * engine makes of the same rule file is reported separately, through the rule listing.
 */

/** The filesystem the configuration files need. Tests substitute their own. */
export interface JsonFileSystem {
  readText(path: string): Promise<string>;
  writeText(path: string, text: string): Promise<void>;
  exists(path: string): Promise<boolean>;
}

/** Reads a JSON file through `normalize`, falling back to `empty` whenever it cannot be read. */
export async function readJsonFile<T>(
  fs: JsonFileSystem,
  path: string,
  normalize: (value: unknown) => T,
  empty: () => T,
): Promise<T> {
  if (!(await fs.exists(path))) {
    return empty();
  }
  try {
    const text = await fs.readText(path);
    if (text.trim() === "") {
      return empty();
    }
    return normalize(parseJson(text));
  } catch {
    return empty();
  }
}

/** What the bytes of a JSON file hold. */
export type JsonContent =
  | { readonly kind: "empty" }
  | { readonly kind: "value"; readonly value: unknown }
  /** Bytes that are no UTF-8, or text that is no JSON. */
  | { readonly kind: "unreadable" };

/** UTF-8 read strictly. The byte order mark is kept here and dropped in parseJson. */
const UTF8 = new TextDecoder("utf-8", { fatal: true, ignoreBOM: true });

/** Reads the bytes of a JSON file: decoded as UTF-8, where a byte of any other encoding fails, then parsed. */
export function parseJsonBytes(bytes: Uint8Array): JsonContent {
  let text: string;
  try {
    text = UTF8.decode(bytes);
  } catch {
    return { kind: "unreadable" };
  }
  if (text.trim() === "") {
    return { kind: "empty" };
  }
  try {
    return { kind: "value", value: parseJson(text) };
  } catch {
    return { kind: "unreadable" };
  }
}

function parseJson(text: string): unknown {
  // A byte order mark, which some editors put in front, is no part of the JSON.
  return JSON.parse(text.replace(/^\uFEFF/, ""));
}

/** Writes a JSON file indented and newline-terminated, so a person can read and edit it. */
export async function writeJsonFile(
  fs: JsonFileSystem,
  path: string,
  value: unknown,
): Promise<void> {
  await fs.writeText(path, `${JSON.stringify(value, null, 2)}\n`);
}
