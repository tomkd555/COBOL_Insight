import { describe, expect, it } from "vitest";
import type { LineMapEntry } from "../../shared/ipc";
import { generatedLanguage, readGeneratedFiles, readLineMap, type GeneratedFileSystem } from "./transpile";
import { openDatabaseWith } from "./fixtures";

const LINE_MAP_DB = `
  CREATE TABLE SOURCE (id INTEGER PRIMARY KEY, path TEXT);
  CREATE TABLE LINE_MAP (
    id INTEGER PRIMARY KEY, cobol_source_id INTEGER, cobol_line_start INTEGER,
    cobol_line_end INTEGER, gen_file TEXT, gen_line_start INTEGER, gen_line_end INTEGER,
    kind TEXT, note TEXT, anchor_id TEXT);
  INSERT INTO SOURCE VALUES (1, 'cobol/SYK001.cbl'), (2, 'cobol/SYK002.cbl');
  INSERT INTO LINE_MAP VALUES
    (1, 1, 20, 20, 'syk001.py', 8, 8, '1:1', '', ''),
    (2, 1, 10, 10, 'Syk001.java', 3, 3, '1:1', 'a full-width name cannot be translated literally', 'mainProc'),
    (3, 2, 5, 5, 'syk002.py', 2, 2, '1:1', '', '');
`;

function files(contents: Record<string, string>): GeneratedFileSystem {
  return {
    list: async () => Object.keys(contents),
    readText: async (absPath) => {
      const name = absPath.replace(/\\/g, "/").split("/").pop() ?? "";
      return contents[name];
    },
  };
}

describe("readLineMap", () => {
  it("returns only the rows of the requested source", async () => {
    const db = await openDatabaseWith(LINE_MAP_DB);
    try {
      const entries = readLineMap(db, "cobol/SYK001.cbl");
      expect(entries.map((entry) => entry.genFile)).toEqual(["Syk001.java", "syk001.py"]);
    } finally {
      db.close();
    }
  });

  it("keeps the note that says what could not be translated literally", async () => {
    const db = await openDatabaseWith(LINE_MAP_DB);
    try {
      expect(readLineMap(db, "cobol/SYK001.cbl")[0].note).toContain("cannot be translated");
    } finally {
      db.close();
    }
  });

  it("returns nothing for a source with no translation", async () => {
    const db = await openDatabaseWith(LINE_MAP_DB);
    try {
      expect(readLineMap(db, "cobol/UNKNOWN.cbl")).toEqual([]);
    } finally {
      db.close();
    }
  });
});

describe("generatedLanguage", () => {
  it("reads the language off the extension and rejects anything else", () => {
    expect(generatedLanguage("a.py")).toBe("python");
    expect(generatedLanguage("A.JAVA")).toBe("java");
    expect(generatedLanguage("runtime.txt")).toBeNull();
  });
});

describe("readGeneratedFiles", () => {
  const lineMap = [
    { genFile: "syk001.py" },
    { genFile: "Syk001.java" },
  ] as unknown as LineMapEntry[];

  it("reads the referenced files in name order", async () => {
    const result = await readGeneratedFiles(
      files({ "syk001.py": "py", "Syk001.java": "java", "other.py": "no" }),
      "C:/gen",
      lineMap,
    );
    expect(result.map((file) => file.name)).toEqual(["Syk001.java", "syk001.py"]);
    expect(result[0].language).toBe("java");
  });

  it("skips a reference the output directory no longer holds", async () => {
    const result = await readGeneratedFiles(files({ "syk001.py": "py" }), "C:/gen", lineMap);
    expect(result.map((file) => file.name)).toEqual(["syk001.py"]);
  });

  it("reads nothing when the line map references nothing", async () => {
    expect(await readGeneratedFiles(files({ "a.py": "x" }), "C:/gen", [])).toEqual([]);
  });
});
