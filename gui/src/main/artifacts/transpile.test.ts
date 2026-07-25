import { describe, it, expect, beforeAll, vi } from "vitest";
import { createRequire } from "node:module";
import { join } from "node:path";
import initSqlJs, { type Database } from "sql.js";
import {
  generatedLanguage,
  readGeneratedFiles,
  readLineMap,
  type GeneratedFileSystem,
} from "./transpile";
import type { LineMapEntry } from "../../shared/engine-api";

const require = createRequire(import.meta.url);

let db: Database;

/** engine の Schema.java と同じ列構成で SOURCE・LINE_MAP を組み、対訳の対応表を投入する。 */
beforeAll(async () => {
  const wasmPath = require.resolve("sql.js/dist/sql-wasm.wasm");
  const SQL = await initSqlJs({ locateFile: () => wasmPath });
  db = new SQL.Database();
  db.run(`
    CREATE TABLE SOURCE (
      id INTEGER PRIMARY KEY, path TEXT NOT NULL, codepage TEXT,
      content_hash TEXT NOT NULL, byte_size INTEGER NOT NULL
    );
    CREATE TABLE LINE_MAP (
      id INTEGER PRIMARY KEY,
      cobol_source_id INTEGER NOT NULL REFERENCES SOURCE(id) ON DELETE CASCADE,
      cobol_line_start INTEGER NOT NULL, cobol_line_end INTEGER NOT NULL,
      gen_file TEXT NOT NULL,
      gen_line_start INTEGER NOT NULL, gen_line_end INTEGER NOT NULL,
      kind TEXT NOT NULL, note TEXT NOT NULL, anchor_id TEXT NOT NULL
    );
    INSERT INTO SOURCE VALUES
      (1, 'cobol/SYK001.cbl', 'Shift_JIS', 'h1', 100),
      (2, 'cobol/SYK002.cbl', 'UTF-8', 'h2', 200);
    INSERT INTO LINE_MAP VALUES
      (13, 1, 20, 22, 'syk001.py', 40, 45, 'STATEMENT', 'GO TO は直訳できない', 's:1'),
      (12, 1, 12, 12, 'syk001.py', 30, 34, 'PARAGRAPH', '', 'p:MAIN'),
      (11, 1, 12, 12, 'Syk001.java', 50, 55, 'PARAGRAPH', '', 'p:MAIN'),
      (20, 2, 5, 5, 'syk002.py', 10, 11, 'STATEMENT', '', 's:9');
  `);
});

describe("readLineMap(LINE_MAP と SOURCE の結合)", () => {
  it("指定した COBOL 相対パスの対応だけを、生成ファイル・生成開始行の順で返す", () => {
    const entries = readLineMap(db, "cobol/SYK001.cbl");
    expect(entries.map((entry) => [entry.genFile, entry.genLineStart])).toEqual([
      ["Syk001.java", 50],
      ["syk001.py", 30],
      ["syk001.py", 40],
    ]);
  });

  it("行範囲・種別・注記・アンカーを列名で写す", () => {
    const entries = readLineMap(db, "cobol/SYK001.cbl");
    const withNote = entries.find((entry) => entry.note !== "");
    expect(withNote).toEqual<LineMapEntry>({
      id: 13,
      cobolLineStart: 20,
      cobolLineEnd: 22,
      genFile: "syk001.py",
      genLineStart: 40,
      genLineEnd: 45,
      kind: "STATEMENT",
      note: "GO TO は直訳できない",
      anchorId: "s:1",
    });
  });

  it("他ソースの対応を混ぜない", () => {
    expect(readLineMap(db, "cobol/SYK002.cbl").map((entry) => entry.id)).toEqual([20]);
  });

  it("対応の無い相対パスでは空を返す", () => {
    expect(readLineMap(db, "cobol/UNKNOWN.cbl")).toEqual([]);
  });
});

describe("generatedLanguage(拡張子からの言語判定)", () => {
  it("py は python、java は java と判定する", () => {
    expect(generatedLanguage("syk001.py")).toBe("python");
    expect(generatedLanguage("Syk001.java")).toBe("java");
  });

  it("大文字の拡張子も判定する", () => {
    expect(generatedLanguage("SYK001.PY")).toBe("python");
  });

  it("対訳の対象外の拡張子は null を返す", () => {
    expect(generatedLanguage("readme.txt")).toBeNull();
    expect(generatedLanguage("syk001")).toBeNull();
  });
});

describe("readGeneratedFiles(出力先直下の生成物読取)", () => {
  const lineMap: LineMapEntry[] = [
    {
      id: 1,
      cobolLineStart: 12,
      cobolLineEnd: 12,
      genFile: "syk001.py",
      genLineStart: 30,
      genLineEnd: 34,
      kind: "PARAGRAPH",
      note: "",
      anchorId: "p:MAIN",
    },
    {
      id: 2,
      cobolLineStart: 12,
      cobolLineEnd: 12,
      genFile: "Syk001.java",
      genLineStart: 50,
      genLineEnd: 55,
      kind: "PARAGRAPH",
      note: "",
      anchorId: "p:MAIN",
    },
  ];

  function fakeFs(names: string[]): GeneratedFileSystem {
    return {
      list: vi.fn().mockResolvedValue(names),
      readText: vi.fn().mockImplementation((path: string) => Promise.resolve(`本文: ${path}`)),
    };
  }

  it("対応表が参照する生成物だけを出力先直下から読み、言語を付ける", async () => {
    const fs = fakeFs(["syk001.py", "Syk001.java", "cobol_runtime.py", "syk002.py"]);
    const files = await readGeneratedFiles(fs, "C:/out", lineMap);
    expect(files).toEqual([
      { name: "Syk001.java", language: "java", text: `本文: ${join("C:/out", "Syk001.java")}` },
      { name: "syk001.py", language: "python", text: `本文: ${join("C:/out", "syk001.py")}` },
    ]);
    expect(fs.list).toHaveBeenCalledWith("C:/out");
  });

  it("出力先に無い生成物は飛ばす(過去の出力先の対応表が残っている場合)", async () => {
    const fs = fakeFs(["syk001.py"]);
    const files = await readGeneratedFiles(fs, "C:/out", lineMap);
    expect(files.map((file) => file.name)).toEqual(["syk001.py"]);
  });

  it("対応表が空なら何も読まない", async () => {
    const fs = fakeFs(["syk001.py"]);
    await expect(readGeneratedFiles(fs, "C:/out", [])).resolves.toEqual([]);
    expect(fs.readText).not.toHaveBeenCalled();
  });
});
