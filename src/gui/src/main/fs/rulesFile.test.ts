import { describe, expect, it } from "vitest";
import { RULES_FILE_VERSION, emptyRulesFile, type RulesFile } from "../../shared/rulesFile";
import { readRulesFile, writeRulesFile } from "./rulesFile";
import type { JsonFileSystem } from "./jsonFile";

function memory(initial: Record<string, string> = {}): JsonFileSystem & { files: Map<string, string> } {
  const files = new Map(Object.entries(initial));
  return {
    files,
    readText: async (path) => {
      const text = files.get(path);
      if (text === undefined) throw new Error("no such file");
      return text;
    },
    writeText: async (path, text) => {
      files.set(path, text);
    },
    exists: async (path) => files.has(path),
  };
}

const PATH = "C:/data/rules.json";

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
    await expect(readRulesFile(memory(), PATH)).resolves.toEqual(emptyRulesFile());
  });

  it("returns an empty configuration rather than failing on corrupt text", async () => {
    await expect(readRulesFile(memory({ [PATH]: "{oops" }), PATH)).resolves.toEqual(emptyRulesFile());
  });

  it("keeps only the override fields it understands", async () => {
    const stored = JSON.stringify({ rules: { R001: { enabled: false, colour: "red" }, R002: {} } });
    const file = await readRulesFile(memory({ [PATH]: stored }), PATH);
    expect(file.rules).toEqual({ R001: { enabled: false }, R002: {} });
  });

  it("drops a custom rule with no id, which nothing could address", async () => {
    const stored = JSON.stringify({ custom: [{ name: "no id" }, { id: "U001", name: "kept" }] });
    const file = await readRulesFile(memory({ [PATH]: stored }), PATH);
    expect(file.custom.map((rule) => rule.id)).toEqual(["U001"]);
  });

  it("carries a custom rule across untouched, so no field is lost and none is invented", async () => {
    const definition = { id: "U001", name: "kept", match: { kind: "statement", verb: ["READ"] } };
    const stored = JSON.stringify({ custom: [definition] });
    const file = await readRulesFile(memory({ [PATH]: stored }), PATH);
    expect(file.custom[0]).toEqual(definition);
  });
});

describe("writeRulesFile", () => {
  it("round-trips through the file", async () => {
    const fs = memory();
    await writeRulesFile(fs, PATH, FULL);
    await expect(readRulesFile(fs, PATH)).resolves.toEqual(FULL);
  });

  it("stamps the version the engine expects, whatever the caller supplied", async () => {
    const fs = memory();
    await writeRulesFile(fs, PATH, { ...FULL, version: 99 });
    expect(JSON.parse(fs.files.get(PATH) ?? "").version).toBe(RULES_FILE_VERSION);
  });
});
