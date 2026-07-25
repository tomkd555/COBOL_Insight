import { describe, it, expect, beforeAll } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";
import initSqlJs from "sql.js";
import { readInventory } from "./inventory";
import type { QueryableDatabase } from "./sqlRows";

const require = createRequire(import.meta.url);

let sampleDb: QueryableDatabase;

beforeAll(async () => {
  const wasmPath = require.resolve("sql.js/dist/sql-wasm.wasm");
  const SQL = await initSqlJs({ locateFile: () => wasmPath });
  const bytes = readFileSync(join(__dirname, "..", "__fixtures__", "sample.db"));
  sampleDb = new SQL.Database(bytes);
});

describe("readInventory", () => {
  it("実 scan 済み SQLite から16件の資産を種別・コードページ付きで読む", () => {
    const items = readInventory(sampleDb);
    expect(items).toHaveLength(16);

    const bms = items.find((i) => i.path === "bms/SYKMAP1.bms");
    expect(bms).toMatchObject({
      id: 1,
      name: "SYKMAP1.bms",
      type: "BMS",
      codepage: "UTF-8",
      byteSize: 1140,
      findingCount: 0,
    });

    const program = items.find((i) => i.path === "cobol/SYK001.cbl");
    expect(program?.type).toBe("PROGRAM");

    const copybook = items.find((i) => i.path === "copybook/SYKCPY1.cpy");
    expect(copybook?.type).toBe("COPYBOOK");

    const jcl = items.find((i) => i.path === "jcl/SYKD010.jcl");
    expect(jcl?.type).toBe("JCL");
  });

  it("グラフ層の finding(id ≥ 1e12)を scan 由来の件数から除く", () => {
    // 前提の確認: sample.db は cobol/SYK002.cbl(SOURCE.id=3)へ紐づくグラフ層 finding を 1 件持つ。
    const [graphLayer] = sampleDb.exec(
      "SELECT s.path AS path, f.id AS id, f.rule_id AS ruleId FROM FINDING f" +
        " JOIN SOURCE s ON s.id = f.source_id WHERE f.id >= 1000000000000",
    );
    expect(graphLayer.values).toHaveLength(1);
    expect(graphLayer.values[0]).toContain("cobol/SYK002.cbl");
    expect(graphLayer.values[0]).toContain("callgraph-dynamic-call");

    // 除外の結果: その資産の findingCount は 0 になり、グラフ層の 1 件を数えない。
    const items = readInventory(sampleDb);
    expect(items.find((i) => i.path === "cobol/SYK002.cbl")?.findingCount).toBe(0);
    expect(items.filter((i) => i.findingCount > 0)).toEqual([]);
  });

  it("path 昇順で並ぶ", () => {
    const items = readInventory(sampleDb);
    const paths = items.map((i) => i.path);
    expect(paths).toEqual([...paths].sort());
  });

  it("列写像で組み立てるため列順に依存しない", () => {
    const stub: QueryableDatabase = {
      exec: () => [
        {
          columns: ["findingCount", "type", "path", "codepage", "byteSize", "id"],
          values: [[3, "PROGRAM", "cobol/X.cbl", null, 100, 42]],
        },
      ],
    };
    expect(readInventory(stub)[0]).toEqual({
      id: 42,
      path: "cobol/X.cbl",
      name: "X.cbl",
      type: "PROGRAM",
      codepage: null,
      byteSize: 100,
      findingCount: 3,
    });
  });
});
