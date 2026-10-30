import { beforeEach, describe, expect, it } from "vitest";
import { RULES_FILE_VERSION, emptyRulesFile, type CustomRule, type RulesFile } from "../../shared/rulesFile";
import { ownRulesPath, readRulesFile, withOwnRulesFile, writeRulesFile, type RulesFileSystem } from "./rulesFile";

type Memory = RulesFileSystem & { files: Map<string, Uint8Array> };

const encode = (text: string): Uint8Array => new TextEncoder().encode(text);

function memory(initial: Record<string, string | Uint8Array> = {}): Memory {
  const files = new Map(
    Object.entries(initial).map(([path, content]): [string, Uint8Array] => [
      path,
      typeof content === "string" ? encode(content) : content,
    ]),
  );
  const bytes = (path: string): Uint8Array => {
    const content = files.get(path);
    if (content === undefined) throw new Error("no such file");
    return content;
  };
  return {
    files,
    readText: async (path) => new TextDecoder().decode(bytes(path)),
    readBytes: async (path) => bytes(path),
    writeText: async (path, text) => {
      files.set(path, encode(text));
    },
    exists: async (path) => files.has(path),
    copyNew: async (from, to) => {
      if (files.has(to)) return false;
      files.set(to, bytes(from).slice());
      return true;
    },
  };
}

/** The text a file holds. */
const textAt = (fs: Memory, path: string): string | undefined => {
  const content = fs.files.get(path);
  return content === undefined ? undefined : new TextDecoder().decode(content);
};

/** The copies kept beside the file, in the order they were made. */
const copiesOf = (fs: Memory, path: string): string[] =>
  [...fs.files.keys()].filter((name) => name.startsWith(`${path}.unreadable-`));

const PATH = "C:/data/rules.json";

// The module keeps what it last served per path, so every test that reads or writes names a path of
// its own, and the tests hold in any order.
let counter = 0;
const fresh = (): string => `C:/data/test-${++counter}/rules.json`;
let path = "";
beforeEach(() => {
  path = fresh();
});

const FULL: RulesFile = {
  version: RULES_FILE_VERSION,
  rules: { R001: { enabled: false }, R004: { severity: "HIGH" } },
  custom: [
    {
      id: "U001",
      name: "console input",
      category: "house rules",
      severity: "MEDIUM",
      targets: ["COBOL"],
      message: "console input is forbidden",
      match: { kind: "line", regex: "FROM\\s+CONSOLE", ignoreCase: true },
    },
  ],
};

describe("readRulesFile", () => {
  it("returns an empty configuration when nothing has been stored", async () => {
    await expect(readRulesFile(memory(), path)).resolves.toEqual(emptyRulesFile());
  });

  it("resolves to an empty configuration on corrupt text", async () => {
    await expect(readRulesFile(memory({ [path]: "{oops" }), path)).resolves.toEqual(emptyRulesFile());
  });

  it("keeps only the override fields it understands", async () => {
    const stored = JSON.stringify({ rules: { R001: { enabled: false, colour: "red" }, R002: {} } });
    const file = await readRulesFile(memory({ [path]: stored }), path);
    expect(file.rules).toEqual({ R001: { enabled: false }, R002: {} });
  });

  it("drops a custom rule with no id, which nothing could address", async () => {
    const stored = JSON.stringify({ custom: [{ name: "no id" }, { id: "U001", name: "kept" }] });
    const file = await readRulesFile(memory({ [path]: stored }), path);
    expect(file.custom.map((rule) => rule.id)).toEqual(["U001"]);
  });

  it("returns a custom rule unchanged", async () => {
    const definition = { id: "U001", name: "kept", match: { kind: "statement", verb: ["READ"] } };
    const stored = JSON.stringify({ custom: [definition] });
    const file = await readRulesFile(memory({ [path]: stored }), path);
    expect(file.custom[0]).toEqual(definition);
  });

  it("resolves to an empty configuration when the read itself fails", async () => {
    const fs = memory({ [path]: "{}" });
    fs.readBytes = async () => {
      throw Object.assign(new Error("EACCES: permission denied, open 'C:\\data\\rules.json'"), { code: "EACCES" });
    };
    await expect(readRulesFile(fs, path)).resolves.toEqual(emptyRulesFile());
  });
});

describe("ownRulesPath", () => {
  const REFUSED = "ルール設定ファイルの場所が一致しません。";

  it("returns the application's own path for the same spelling", () => {
    expect(ownRulesPath(PATH, PATH)).toBe(PATH);
  });

  it("returns the application's own path for a spelling with dot segments", () => {
    expect(ownRulesPath("C:/data/./rules.json", PATH)).toBe(PATH);
    expect(ownRulesPath("C:/data/old/../rules.json", PATH)).toBe(PATH);
  });

  it.runIf(process.platform === "win32")("treats a backslash as the same separator as a slash on Windows", () => {
    expect(ownRulesPath("C:\\data\\rules.json", PATH)).toBe(PATH);
  });

  it("refuses a file of another name in the same folder", () => {
    expect(() => ownRulesPath("C:/data/other.json", PATH)).toThrow(REFUSED);
  });

  it("refuses a file of the same name in another folder", () => {
    expect(() => ownRulesPath("C:/elsewhere/rules.json", PATH)).toThrow(REFUSED);
    expect(() => ownRulesPath("C:/data/sub/rules.json", PATH)).toThrow(REFUSED);
  });

  it("refuses a path that climbs out of the folder to reach the file's name", () => {
    expect(() => ownRulesPath("C:/data/../data2/rules.json", PATH)).toThrow(REFUSED);
  });

  it.each([
    ["a number", 5],
    ["null", null],
    ["an object", { path: PATH }],
  ])("refuses %s with the same Japanese sentence", (_case, named) => {
    expect(() => ownRulesPath(named, PATH)).toThrow(new Error(REFUSED));
  });
});

describe("writeRulesFile", () => {
  it("round-trips through the file", async () => {
    const fs = memory();
    await readRulesFile(fs, path);
    await writeRulesFile(fs, path, FULL);
    await expect(readRulesFile(fs, path)).resolves.toEqual(FULL);
  });

  it("stamps the version the engine expects, whatever the caller supplied", async () => {
    const fs = memory();
    await readRulesFile(fs, path);
    await writeRulesFile(fs, path, { ...FULL, version: 99 });
    expect(JSON.parse(textAt(fs, path) ?? "").version).toBe(RULES_FILE_VERSION);
  });

  describe("over a file that holds more than the renderer knows", () => {
    const parsed = (fs: Memory, path: string): Record<string, unknown> =>
      JSON.parse(textAt(fs, path) ?? "null") as Record<string, unknown>;
    const rulesFile = (rules: RulesFile["rules"], custom: RulesFile["custom"] = []): RulesFile => ({
      version: RULES_FILE_VERSION,
      rules,
      custom,
    });

    it("keeps a top-level member the renderer has no field for", async () => {
      const fs = memory({ [path]: JSON.stringify({ version: 2, rules: {}, custom: [], futureMember: { a: 1 } }) });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: false } }));
      expect(parsed(fs, path)["futureMember"]).toEqual({ a: 1 });
    });

    it("keeps an unknown member of an override when that override changes", async () => {
      const fs = memory({ [path]: JSON.stringify({ rules: { R001: { enabled: false, note: "keep" } } }) });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: true } }));
      expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: true, note: "keep" } });
    });

    it("replaces only enabled and severity of a changed override", async () => {
      const fs = memory({ [path]: JSON.stringify({ rules: { R001: { severity: "HIGH", note: "keep" } } }) });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: false } }));
      expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: false, note: "keep" } });
    });

    it("keeps an unknown member of an override when another override changes", async () => {
      const stored = { R001: { enabled: false, note: "keep" }, R002: { enabled: true } };
      const fs = memory({ [path]: JSON.stringify({ rules: stored }) });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: false }, R002: { enabled: false } }));
      expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: false, note: "keep" }, R002: { enabled: false } });
    });

    it("writes a wrong-typed override as stored when the renderer left it alone", async () => {
      const stored = { R001: { enabled: "yes", severity: 5 }, R002: { enabled: true } };
      const fs = memory({ [path]: JSON.stringify({ rules: stored }) });
      const read = await readRulesFile(fs, path);
      await writeRulesFile(fs, path, { ...read, rules: { ...read.rules, R002: { enabled: false } } });
      expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: "yes", severity: 5 }, R002: { enabled: false } });
    });

    it("deletes an override the renderer removed", async () => {
      const fs = memory({ [path]: JSON.stringify({ rules: { R001: { enabled: false }, R002: { enabled: false } } }) });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: false } }));
      expect(Object.keys(parsed(fs, path)["rules"] as object)).toEqual(["R001"]);
    });

    it("keeps an override added to the file by hand after the last read", async () => {
      const fs = memory({ [path]: JSON.stringify({ rules: { R001: { enabled: false } } }) });
      await readRulesFile(fs, path);
      fs.files.set(path, encode(JSON.stringify({ rules: { R001: { enabled: false }, R009: { severity: "LOW" } } })));
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: true } }));
      expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: true }, R009: { severity: "LOW" } });
    });

    const HAND_MADE = { name: "typed by hand, no id", match: { kind: "line", regex: "X" }, targets: [] };
    const U001: CustomRule = {
      id: "U001",
      name: "one",
      message: "m",
      targets: [],
      match: { kind: "line", regex: "A", excludeRegex: "" },
    };
    const U002: CustomRule = { id: "U002", name: "two", message: "m", match: { kind: "statement", verb: ["READ"] } };
    const U003: CustomRule = { id: "U003", name: "three", message: "m", match: { kind: "line", regex: "C" } };
    const U004: CustomRule = { id: "U004", name: "four", message: "m", match: { kind: "line", regex: "D" } };

    it("writes custom entries as stored when the custom rules are unchanged", async () => {
      const custom = [U001, HAND_MADE, U002];
      const fs = memory({ [path]: JSON.stringify({ rules: {}, custom }) });
      const read = await readRulesFile(fs, path);
      await writeRulesFile(fs, path, { ...read, rules: { R001: { enabled: false } } });
      expect(JSON.stringify(parsed(fs, path)["custom"])).toBe(JSON.stringify(custom));
    });

    it("keeps an unseen entry in place when the custom rules change", async () => {
      const fs = memory({ [path]: JSON.stringify({ custom: [HAND_MADE, U001, U002] }) });
      await readRulesFile(fs, path);
      const renamed = { ...U001, name: "renamed" };
      await writeRulesFile(fs, path, rulesFile({}, [renamed]));
      expect(parsed(fs, path)["custom"]).toEqual([HAND_MADE, renamed]);
    });

    describe("a stored rules member that is no object", () => {
      it("stays as it is while no override changed", async () => {
        const fs = memory({ [path]: JSON.stringify({ rules: ["R1"], custom: [] }) });
        const read = await readRulesFile(fs, path);
        await writeRulesFile(fs, path, { ...read, custom: [U001] });
        expect(parsed(fs, path)["rules"]).toEqual(["R1"]);
        expect(parsed(fs, path)["custom"]).toEqual([U001]);
        expect(copiesOf(fs, path)).toEqual([]);
      });

      it("is copied with the file before an override change writes an object there", async () => {
        const original = JSON.stringify({ rules: ["R1"] });
        const fs = memory({ [path]: original });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({ R001: { enabled: false } }));
        expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: false } });
        const copies = copiesOf(fs, path);
        expect(copies).toHaveLength(1);
        expect(textAt(fs, copies[0])).toBe(original);
      });
    });

    describe("a stored custom member that is no array", () => {
      it("stays as it is while an override is toggled", async () => {
        const fs = memory({ [path]: JSON.stringify({ rules: {}, custom: { id: "U001" } }) });
        const read = await readRulesFile(fs, path);
        await writeRulesFile(fs, path, { ...read, rules: { R001: { enabled: false } } });
        expect(parsed(fs, path)["custom"]).toEqual({ id: "U001" });
        expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: false } });
        expect(copiesOf(fs, path)).toEqual([]);
      });
    });

    describe("an override with members the renderer cannot read", () => {
      it("keeps an enabled of another type when only the severity is set", async () => {
        const fs = memory({ [path]: JSON.stringify({ rules: { R001: { enabled: "no", note: "keep" } } }) });
        const read = await readRulesFile(fs, path);
        await writeRulesFile(fs, path, { ...read, rules: { R001: { severity: "HIGH" } } });
        expect(parsed(fs, path)["rules"]).toEqual({ R001: { enabled: "no", note: "keep", severity: "HIGH" } });
      });

      it("keeps the other members when the severity is reset by removing the override", async () => {
        const fs = memory({ [path]: JSON.stringify({ rules: { R001: { severity: "HIGH", note: "keep" } } }) });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({}));
        expect(parsed(fs, path)["rules"]).toEqual({ R001: { note: "keep" } });
      });

      it("keeps the other members when the severity is reset to an empty override", async () => {
        const fs = memory({ [path]: JSON.stringify({ rules: { R001: { severity: "HIGH", note: "keep" } } }) });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({ R001: {} }));
        expect(parsed(fs, path)["rules"]).toEqual({ R001: { note: "keep" } });
      });
    });

    describe("the custom rules, changed per id", () => {
      it("keeps hand edits on disk when the application adds a definition", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, U002] }) });
        const read = await readRulesFile(fs, path);
        const renamedOnDisk = { ...U001, name: "renamed on disk" };
        fs.files.set(path, encode(JSON.stringify({ custom: [renamedOnDisk, U002, U003] })));
        await writeRulesFile(fs, path, { ...read, custom: [...read.custom, U004] });
        expect(parsed(fs, path)["custom"]).toEqual([renamedOnDisk, U002, U003, U004]);
      });

      it("keeps the order of [U001, an entry with no id, U002] when U002 changes", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, HAND_MADE, U002] }) });
        await readRulesFile(fs, path);
        const changed = { ...U002, name: "changed" };
        await writeRulesFile(fs, path, rulesFile({}, [U001, changed]));
        expect(parsed(fs, path)["custom"]).toEqual([U001, HAND_MADE, changed]);
      });

      it("removes only the definition the renderer removed", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, HAND_MADE, U002] }) });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({}, [U002]));
        expect(parsed(fs, path)["custom"]).toEqual([HAND_MADE, U002]);
      });

      it("puts unseen entries after the renderer's order when it reorders", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, HAND_MADE, U002] }) });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({}, [U002, U001]));
        expect(parsed(fs, path)["custom"]).toEqual([U002, U001, HAND_MADE]);
      });

      it("keeps every entry in place when an id occurs twice", async () => {
        const twin = { ...U001, name: "twin" };
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, HAND_MADE, twin] }) });
        const read = await readRulesFile(fs, path);
        await writeRulesFile(fs, path, { ...read, custom: [...read.custom, U004] });
        expect(parsed(fs, path)["custom"]).toEqual([U001, HAND_MADE, twin, U004]);
      });

      it("keeps a hand edit and an id-less entry when the renderer reorders", async () => {
        const U1: CustomRule = { id: "U1", name: "one", message: "m", match: { kind: "line", regex: "A" } };
        const U2: CustomRule = { id: "U2", name: "two", message: "m", match: { kind: "line", regex: "B" } };
        const NO_ID = { name: "no id" };
        const fs = memory({ [path]: JSON.stringify({ custom: [U1, NO_ID, U2] }) });
        await readRulesFile(fs, path);
        const handEdited = { ...U2, hand: true };
        fs.files.set(path, encode(JSON.stringify({ custom: [U1, NO_ID, handEdited] })));
        await writeRulesFile(fs, path, rulesFile({}, [U2, U1]));
        expect(parsed(fs, path)["custom"]).toEqual([handEdited, U1, NO_ID]);
      });

      it("puts an entry with no id at the end where its neighbours were removed", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [U001, HAND_MADE, U002, U003, U004] }) });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, rulesFile({}, [U004, U003]));
        expect(parsed(fs, path)["custom"]).toEqual([U004, U003, HAND_MADE]);
      });
    });

    describe("definitions that share an id", () => {
      const orig: CustomRule = { id: "U1", name: "orig", message: "m", match: { kind: "line", regex: "A" } };

      it("edits the twin the renderer was shown, keeping the hand-added one", async () => {
        const fs = memory({ [path]: JSON.stringify({ custom: [orig] }) });
        await readRulesFile(fs, path);
        const hand = { ...orig, name: "hand" };
        fs.files.set(path, encode(JSON.stringify({ custom: [hand, orig] })));
        const mod = { ...orig, name: "mod" };
        await writeRulesFile(fs, path, rulesFile({}, [mod]));
        expect(parsed(fs, path)["custom"]).toEqual([hand, mod]);
      });

      it("writes the stored copy of a kept twin when the renderer deletes the other", async () => {
        const a = { ...orig, name: "a" };
        const b = { ...orig, name: "b" };
        const fs = memory({ [path]: JSON.stringify({ custom: [a, b] }) });
        await readRulesFile(fs, path);
        const handEdited = { ...b, hand: true };
        fs.files.set(path, encode(JSON.stringify({ custom: [a, handEdited] })));
        await writeRulesFile(fs, path, rulesFile({}, [b]));
        expect(parsed(fs, path)["custom"]).toEqual([handEdited]);
      });
    });

    it("writes a renamed id in the place of the old one", async () => {
      const fs = memory({ [path]: JSON.stringify({ custom: [U001, U002, U003] }) });
      await readRulesFile(fs, path);
      const renamed = { ...U001, id: "U009" };
      await writeRulesFile(fs, path, rulesFile({}, [renamed, U002, U003]));
      expect(parsed(fs, path)["custom"]).toEqual([renamed, U002, U003]);
    });

    it("refuses a write to an unread path without changing the file", async () => {
      const stored = JSON.stringify({ rules: { R001: { enabled: false } }, custom: [U001] });
      const fs = memory({ [path]: stored });
      await expect(writeRulesFile(fs, path, rulesFile({}, []))).rejects.toThrow(
        new Error(
          "ルール設定ファイルを読み込む前のため、保存できませんでした。アプリケーションを起動し直してから、もう一度保存してください。",
        ),
      );
      expect(textAt(fs, path)).toBe(stored);
    });

    it("copies the file before it writes over an override that is a string", async () => {
      const original = JSON.stringify({ rules: { R1: "off-by-hand" } });
      const fs = memory({ [path]: original });
      await readRulesFile(fs, path);
      await writeRulesFile(fs, path, rulesFile({ R1: { enabled: false } }));
      expect(parsed(fs, path)["rules"]).toEqual({ R1: { enabled: false } });
      expect(copiesOf(fs, path).map((copy) => textAt(fs, copy))).toEqual([original]);
    });

    it("reads an override whose id is __proto__ as an own member", async () => {
      const fs = memory({ [path]: '{"rules":{"__proto__":{"enabled":false}}}' });
      const read = await readRulesFile(fs, path);
      expect(Object.getPrototypeOf(read.rules)).toBe(Object.prototype);
      expect(Object.getOwnPropertyDescriptor(read.rules, "__proto__")?.value).toEqual({ enabled: false });
      expect(Object.keys(read.rules)).toEqual(["__proto__"]);
    });

    it("keeps hand edits made since the read through two writes", async () => {
      const U1: CustomRule = { id: "U1", name: "one", message: "m", match: { kind: "line", regex: "A" } };
      const fs = memory({ [path]: JSON.stringify({ rules: { R1: { enabled: false } }, custom: [U1] }) });
      const read = await readRulesFile(fs, path);
      const handEdited = { ...U1, regex2: "B" };
      fs.files.set(
        path,
        encode(JSON.stringify({ rules: { R1: { enabled: false }, R9: { severity: "LOW" } }, custom: [handEdited] })),
      );

      const first = { ...read, rules: { ...read.rules, R2: { enabled: false } } };
      await writeRulesFile(fs, path, first);
      expect(parsed(fs, path)).toMatchObject({ rules: { R9: { severity: "LOW" }, R2: { enabled: false } }, custom: [handEdited] });

      await writeRulesFile(fs, path, { ...first, rules: { ...first.rules, R3: { severity: "HIGH" } } });
      expect(parsed(fs, path)["rules"]).toEqual({
        R1: { enabled: false },
        R9: { severity: "LOW" },
        R2: { enabled: false },
        R3: { severity: "HIGH" },
      });
      expect(parsed(fs, path)["custom"]).toEqual([handEdited]);
    });

    describe("an unreadable file", () => {
      // 「日本」 in Shift_JIS: 0x93 cannot start a UTF-8 sequence.
      const SHIFT_JIS = new Uint8Array([0x7b, 0x22, 0x6e, 0x22, 0x3a, 0x22, 0x93, 0xfa, 0x96, 0x7b, 0x22, 0x7d]);
      // {"n":1} in UTF-16 little-endian with its byte order mark.
      const UTF16 = new Uint8Array([0xff, 0xfe, ...Array.from('{"n":1}').flatMap((char) => [char.charCodeAt(0), 0])]);

      it.each([
        ["Shift_JIS", SHIFT_JIS],
        ["UTF-16", UTF16],
      ])("reads a %s file as an empty configuration", async (_encoding, bytes) => {
        await expect(readRulesFile(memory({ [path]: bytes }), path)).resolves.toEqual(emptyRulesFile());
      });

      it.each([
        ["Shift_JIS", SHIFT_JIS],
        ["UTF-16", UTF16],
      ])("copies a %s file byte for byte before the write", async (_encoding, bytes) => {
        const fs = memory({ [path]: bytes });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, FULL);
        const copies = copiesOf(fs, path);
        expect(copies).toHaveLength(1);
        expect(fs.files.get(copies[0])).toEqual(bytes);
        expect(parsed(fs, path)).toMatchObject({ version: RULES_FILE_VERSION, rules: FULL.rules, custom: FULL.custom });
      });

      it("is copied before the write when its text fails to parse", async () => {
        const fs = memory({ [path]: "{ typed by hand" });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, FULL);
        const copies = copiesOf(fs, path);
        expect(copies.map((copy) => textAt(fs, copy))).toEqual(["{ typed by hand"]);
        expect(parsed(fs, path)["version"]).toBe(RULES_FILE_VERSION);
      });

      it("is copied when its text parses to an array", async () => {
        const fs = memory({ [path]: "[1, 2]" });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, FULL);
        expect(copiesOf(fs, path).map((copy) => textAt(fs, copy))).toEqual(["[1, 2]"]);
      });

      it("makes a separate copy for each unreadable file", async () => {
        const fs = memory({ [path]: "{ first" });
        await readRulesFile(fs, path);
        await writeRulesFile(fs, path, FULL);
        fs.files.set(path, encode("{ second"));
        await writeRulesFile(fs, path, FULL);
        expect(copiesOf(fs, path).map((copy) => textAt(fs, copy)).sort()).toEqual(["{ first", "{ second"]);
      });

      it("writes everything the renderer sends", async () => {
        const fs = memory({ [path]: JSON.stringify(FULL) });
        await readRulesFile(fs, path);
        fs.files.set(path, encode("{ broken since it was read"));
        await writeRulesFile(fs, path, FULL);
        await expect(readRulesFile(fs, path)).resolves.toEqual(FULL);
      });
    });

    it("reads and writes a BOM-prefixed file without making a copy", async () => {
      const fs = memory({
        [path]: `\uFEFF${JSON.stringify({ futureMember: 1, rules: { R001: { enabled: false, note: "keep" } } })}`,
      });
      expect(fs.files.get(path)?.slice(0, 3)).toEqual(new Uint8Array([0xef, 0xbb, 0xbf]));
      await expect(readRulesFile(fs, path)).resolves.toEqual(rulesFile({ R001: { enabled: false } }));
      await writeRulesFile(fs, path, rulesFile({ R001: { enabled: true } }));
      expect(parsed(fs, path)).toMatchObject({ futureMember: 1, rules: { R001: { enabled: true, note: "keep" } } });
      expect(copiesOf(fs, path)).toEqual([]);
    });

    describe("a failed save", () => {
      /** The file as the renderer read it, whose filesystem then refuses one call with the given code. */
      const failing = async (member: "readBytes" | "writeText", code: string | undefined): Promise<Memory> => {
        const fs = memory({ [path]: "{}" });
        await readRulesFile(fs, path);
        fs[member] = async () => {
          throw Object.assign(new Error("EPERM: operation not permitted, open 'C:\\data\\rules.json'"), { code });
        };
        return fs;
      };
      const ADVICE = "rules.json をほかのプログラムが開いている場合は閉じ、読み取り専用の場合は解除してから、もう一度保存してください。";

      it("reaches the caller in Japanese with the code where the read fails", async () => {
        await expect(writeRulesFile(await failing("readBytes", "EACCES"), path, FULL)).rejects.toThrow(
          new Error(`ルール設定ファイルを保存できませんでした（EACCES）。${ADVICE}`),
        );
      });

      it("reaches the caller in Japanese with the code where the write fails", async () => {
        await expect(writeRulesFile(await failing("writeText", "EPERM"), path, FULL)).rejects.toThrow(
          new Error(`ルール設定ファイルを保存できませんでした（EPERM）。${ADVICE}`),
        );
      });

      it("leaves the parentheses out for an error with no code", async () => {
        await expect(writeRulesFile(await failing("writeText", undefined), path, FULL)).rejects.toThrow(
          new Error(`ルール設定ファイルを保存できませんでした。${ADVICE}`),
        );
      });
    });
  });
});

describe("withOwnRulesFile", () => {
  const REFUSED = "ルール設定ファイルの場所が一致しません。";

  it("passes a request with no rulesFile through unchanged", () => {
    const request = { source: "a.cbl" };
    expect(withOwnRulesFile(request, PATH)).toBe(request);
  });

  it("puts the application's own path in the place of another spelling of it", () => {
    const request = { source: "a.cbl", rulesFile: "C:/data/./rules.json" };
    expect(withOwnRulesFile(request, PATH)).toEqual({ source: "a.cbl", rulesFile: PATH });
  });

  it("throws when the request names another file", () => {
    expect(() => withOwnRulesFile({ rulesFile: "C:/elsewhere/rules.json" }, PATH)).toThrow(REFUSED);
  });

  it("throws when rulesFile holds an array or null", () => {
    expect(() => withOwnRulesFile({ rulesFile: [PATH] }, PATH)).toThrow(REFUSED);
    expect(() => withOwnRulesFile({ rulesFile: null }, PATH)).toThrow(REFUSED);
  });

  it("throws the same Japanese sentence for a number or an object", () => {
    expect(() => withOwnRulesFile({ rulesFile: 5 }, PATH)).toThrow(new Error(REFUSED));
    expect(() => withOwnRulesFile({ rulesFile: { path: PATH } }, PATH)).toThrow(new Error(REFUSED));
  });
});
