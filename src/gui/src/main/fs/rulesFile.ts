import { basename, resolve } from "node:path";
import type { CustomRule, RuleOverride, RulesFile } from "../../shared/rulesFile";
import { RULES_FILE_VERSION, emptyRulesFile, normalizeRulesFile } from "../../shared/rulesFile";
import { parseJsonBytes, writeJsonFile, type JsonFileSystem } from "./jsonFile";

/**
 * The one rule configuration file (rules.json under userData). The GUI writes it and the engine
 * reads it through --rules.
 */

export const RULES_FILE_NAME = "rules.json";

/**
 * The rule file the application keeps, where the path a caller names is that file. The renderer
 * hands the path back to main; any other path, and a value that is no text, is refused, so the rule
 * channels reach one file only.
 */
export function ownRulesPath(named: unknown, own: string): string {
  if (typeof named !== "string" || resolve(named) !== resolve(own)) {
    throw new Error("ルール設定ファイルの場所が一致しません。");
  }
  return own;
}

/**
 * An engine request with the application's own rule file in the place of the one the renderer
 * named, so the engine is handed the path main checked. Throws when the request names any other
 * file, or holds something other than a path there; a request without a `rulesFile` passes as it is.
 */
export function withOwnRulesFile<T>(request: T, own: string): T {
  const named = (request as { rulesFile?: unknown } | null)?.rulesFile;
  if (named === undefined) {
    return request;
  }
  return { ...request, rulesFile: ownRulesPath(named, own) };
}

/** The filesystem the rule file needs: the JSON files' own, and the file's bytes as they stand. */
export interface RulesFileSystem extends JsonFileSystem {
  readBytes(path: string): Promise<Uint8Array>;
  /** Copies the file byte for byte to a name nothing has; false where something has that name. */
  copyNew(from: string, to: string): Promise<boolean>;
}

/**
 * What the renderer was last given by a read or last sent in a write, by path: what its next write
 * is a change to. The renderer knows the overrides and the custom rules as it holds them; the file
 * on disk can hold more — members a person typed, members of a newer version — and can have been
 * edited since it was read.
 */
const served = new Map<string, RulesFile>();

/** Reads the rule file; a file that is absent or unreadable, or a failed read, yields an empty configuration. */
export async function readRulesFile(fs: RulesFileSystem, path: string): Promise<RulesFile> {
  let file = emptyRulesFile();
  try {
    file = normalizeRulesFile((await storedObject(fs, path)) ?? {});
  } catch {
    // The rule screens start from the engine's defaults, as for a file that is not there.
  }
  served.set(path, file);
  return file;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

/** Sets a member by definition, since assigning to a member named `__proto__` would set the prototype. */
function put(target: Record<string, unknown>, key: string, value: unknown): void {
  Object.defineProperty(target, key, { value, enumerable: true, writable: true, configurable: true });
}

/**
 * The object the file holds now: an empty one for a file that is absent or blank, and null for a
 * file that is unreadable — bytes that are no UTF-8, text that is no JSON, or JSON whose top level
 * is no object.
 */
async function storedObject(fs: RulesFileSystem, path: string): Promise<Record<string, unknown> | null> {
  if (!(await fs.exists(path))) {
    return {};
  }
  const content = parseJsonBytes(await fs.readBytes(path));
  if (content.kind === "empty") {
    return {};
  }
  return content.kind === "value" && isObject(content.value) ? content.value : null;
}

/**
 * Copies the file byte for byte beside itself under a name no earlier copy has, so what a person
 * typed into it outlives the write that follows.
 */
async function keepCopy(fs: RulesFileSystem, path: string): Promise<void> {
  const stamp = new Date().toISOString().replace(/[-:.]/g, "");
  for (let attempt = 0; ; attempt += 1) {
    if (await fs.copyNew(path, `${path}.unreadable-${stamp}${attempt === 0 ? "" : `-${attempt}`}`)) {
      return;
    }
  }
}

const same = (one: unknown, other: unknown): boolean => JSON.stringify(one) === JSON.stringify(other);

/**
 * Lays the change of one override over the stored one: a member whose value changed is replaced, or
 * removed where its value went. An override the renderer removed loses both members, and goes only
 * once nothing else remains in it. Answers whether the stored override is a value of another type,
 * which the change writes over.
 */
function changeOverride(
  rules: Record<string, unknown>,
  id: string,
  was: RuleOverride | undefined,
  now: RuleOverride | undefined,
): boolean {
  const lost = rules[id] !== undefined && !isObject(rules[id]);
  const kept: Record<string, unknown> = isObject(rules[id]) ? { ...(rules[id] as Record<string, unknown>) } : {};
  for (const member of ["enabled", "severity"] as const) {
    const value = now?.[member];
    if (now === undefined || (value === undefined && was?.[member] !== undefined)) {
      delete kept[member];
    } else if (value !== was?.[member]) {
      kept[member] = value;
    }
  }
  if (now === undefined && Object.keys(kept).length === 0) {
    delete rules[id];
  } else {
    put(rules, id, kept);
  }
  return lost;
}

/** The id a stored entry is shown by; undefined for an entry with none. */
function entryId(entry: unknown): string | undefined {
  const id = isObject(entry) ? entry["id"] : undefined;
  return typeof id === "string" && id !== "" ? id : undefined;
}

/**
 * Pairs each entry of `from` with an entry of `to` that has the same id: equal entries first, then
 * the rest of each id in their order. The answer holds, per index of `from`, the index in `to`, or
 * -1 for an entry left without a pair.
 */
function pairById(from: readonly unknown[], to: readonly unknown[]): number[] {
  // ponytail: quadratic in the number of custom rules, which a person writes one by one.
  const pairs = from.map(() => -1);
  const taken = new Set<number>();
  for (const equalOnly of [true, false]) {
    from.forEach((entry, at) => {
      const id = entryId(entry);
      if (pairs[at] !== -1 || id === undefined) {
        return;
      }
      const match = to.findIndex(
        (other, there) => !taken.has(there) && entryId(other) === id && (!equalOnly || same(entry, other)),
      );
      if (match !== -1) {
        pairs[at] = match;
        taken.add(match);
      }
    });
  }
  return pairs;
}

/**
 * The custom rules changed per definition. Each definition the renderer was shown is paired with the
 * one it sends and with the stored entry it was read from (pairById); a shown definition whose id
 * left, with a new id at the same index of what the renderer sends, was renamed in place.
 *
 * A stored definition the renderer sends as it was shown is kept as stored, with what was typed into
 * it since the read; one it changed is replaced; one it removed goes. The stored entries it never
 * saw stay where they are, and new definitions follow. Where the renderer changed the order of the
 * definitions it was shown, its order holds, and an entry it never saw follows the stored entry
 * before it, or precedes the one after it, where that neighbour is sent; otherwise it goes to the end.
 */
function changeCustom(
  stored: readonly unknown[],
  before: readonly CustomRule[],
  incoming: readonly CustomRule[],
): unknown[] {
  const sentOf = pairById(before, incoming);
  const shownIds = new Set(before.map((rule) => rule.id));
  before.forEach((_, at) => {
    if (sentOf[at] === -1 && at < incoming.length && !sentOf.includes(at) && !shownIds.has(incoming[at].id)) {
      sentOf[at] = at;
    }
  });
  const shownOf = pairById(stored, before);
  const shownAt = (sent: number): number => sentOf.indexOf(sent);
  const storedAt = (shown: number): number => shownOf.indexOf(shown);
  /** What is written for the definition the renderer sends at `sent`. */
  const written = (sent: number): unknown => {
    const shown = shownAt(sent);
    const at = shown === -1 ? -1 : storedAt(shown);
    return at !== -1 && same(incoming[sent], before[shown]) ? stored[at] : incoming[sent];
  };

  const order = sentOf.filter((sent) => sent !== -1);
  if (order.every((sent, at) => at === 0 || sent > order[at - 1])) {
    const changed = stored.flatMap((entry, at) => {
      const shown = shownOf[at];
      if (shown === -1) {
        return [entry];
      }
      return sentOf[shown] === -1 ? [] : [written(sentOf[shown])];
    });
    // A definition the renderer changed after it was taken out of the file by hand comes back with it.
    const added = incoming.flatMap((rule, sent) => {
      const shown = shownAt(sent);
      return shown === -1 || (storedAt(shown) === -1 && !same(rule, before[shown])) ? [rule] : [];
    });
    return [...changed, ...added];
  }

  const groups = incoming.map((_, sent) => ({ lead: [] as unknown[], entry: written(sent), follow: [] as unknown[] }));
  const end: unknown[] = [];
  let run: unknown[] = [];
  let after: number | undefined;
  const place = (next: number | undefined): void => {
    if (after !== undefined) {
      groups[after].follow.push(...run);
    } else if (next !== undefined) {
      groups[next].lead.push(...run);
    } else {
      end.push(...run);
    }
    run = [];
  };
  stored.forEach((entry, at) => {
    const shown = shownOf[at];
    if (shown === -1) {
      run.push(entry);
      return;
    }
    const sent = sentOf[shown] === -1 ? undefined : sentOf[shown];
    place(sent);
    after = sent;
  });
  place(undefined);
  return [...groups.flatMap((group) => [...group.lead, group.entry, ...group.follow]), ...end];
}

/**
 * Writes the rule file. The write is the change between what the renderer was last given or last
 * sent and what it sends now, laid over the file as it stands, so the members and values the user
 * did not change through the application keep what they hold on disk. A file that has to lose what
 * it holds to take the change is copied beside itself first: an unreadable one, one whose `rules` or
 * `custom` member is a value of another type where those changed, or one whose changed override is
 * a value of another type. The file is written through a JSON serialiser: the version is the
 * application's stamp, and numbers, escapes and duplicate keys take the serialiser's own form.
 *
 * A write for a path this process has given the renderer nothing for is refused, since nothing
 * tells what the renderer changed. A failure reaches the caller as a Japanese message naming the
 * file and the filesystem's code.
 */
export async function writeRulesFile(
  fs: RulesFileSystem,
  path: string,
  file: RulesFile,
): Promise<void> {
  const given = served.get(path);
  if (given === undefined) {
    throw new Error(
      "ルール設定ファイルを読み込む前のため、保存できませんでした。アプリケーションを起動し直してから、もう一度保存してください。",
    );
  }
  try {
    await writeChange(fs, path, given, normalizeRulesFile(file));
  } catch (error) {
    // What the filesystem says is English and names the full path.
    const code = (error as { code?: unknown } | null)?.code;
    throw new Error(
      `ルール設定ファイルを保存できませんでした${typeof code === "string" ? `（${code}）` : ""}。` +
        `${basename(path)} をほかのプログラムが開いている場合は閉じ、読み取り専用の場合は解除してから、もう一度保存してください。`,
    );
  }
}

async function writeChange(fs: RulesFileSystem, path: string, given: RulesFile, incoming: RulesFile): Promise<void> {
  const stored = await storedObject(fs, path);
  // An unreadable file leaves nothing to lay the change over, so everything the renderer sends is written.
  const before = stored === null ? emptyRulesFile() : given;
  const base = stored ?? {};
  let copy = stored === null;

  let rules: unknown = "rules" in base ? base["rules"] : {};
  const ids = new Set([...Object.keys(before.rules), ...Object.keys(incoming.rules)]);
  const changedIds = [...ids].filter((id) => !same(incoming.rules[id], before.rules[id]));
  if (changedIds.length > 0) {
    copy ||= !isObject(rules);
    const object: Record<string, unknown> = isObject(rules) ? { ...rules } : {};
    for (const id of changedIds) {
      copy = changeOverride(object, id, before.rules[id], incoming.rules[id]) || copy;
    }
    rules = object;
  }

  let custom: unknown = "custom" in base ? base["custom"] : [];
  if (!same(incoming.custom, before.custom)) {
    copy ||= !Array.isArray(custom);
    custom = changeCustom(Array.isArray(custom) ? custom : [], before.custom, incoming.custom);
  }

  if (copy) {
    await keepCopy(fs, path);
  }
  await writeJsonFile(fs, path, { ...base, version: RULES_FILE_VERSION, rules, custom });
  served.set(path, incoming);
}
