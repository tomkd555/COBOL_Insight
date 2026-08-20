import { describe, it, expect } from "vitest";
import type { AssetInventoryItem } from "../../../shared/engine-api";
import {
  assetTypeOf,
  buildTreeRows,
  countFindingsByFile,
  toggleCollapsed,
  type TreeOptions,
} from "./assetTreeModel";

function asset(path: string, type: string): AssetInventoryItem {
  return {
    id: path.length,
    path,
    name: path.split("/").pop() ?? path,
    type,
    codepage: "windows-31j",
    byteSize: 100,
    findingCount: 0,
  };
}

const ITEMS: readonly AssetInventoryItem[] = [
  asset("src/cobol/SYK002.cbl", "PROGRAM"),
  asset("src/cobol/SYK001.cbl", "PROGRAM"),
  asset("src/copy/SYKCPY1.cpy", "COPYBOOK"),
  asset("JOB1.jcl", "JCL"),
  asset("src/MAP1.bms", "BMS"),
];

function options(overrides: Partial<TreeOptions> = {}): TreeOptions {
  return { search: "", type: "all", collapsed: new Set(), findingCounts: {}, ...overrides };
}

describe("assetTypeOf(NODE.type の対応)", () => {
  it("engine の種別を符号へ写し取り、未知は other にする", () => {
    expect(assetTypeOf("PROGRAM")).toBe("cobol");
    expect(assetTypeOf("COPYBOOK")).toBe("copybook");
    expect(assetTypeOf("JCL")).toBe("jcl");
    expect(assetTypeOf("BMS")).toBe("bms");
    expect(assetTypeOf("DATASET")).toBe("other");
    expect(assetTypeOf("UNKNOWN")).toBe("other");
  });
});

describe("buildTreeRows(ツリーの行)", () => {
  it("フォルダを先、資産を後に置き、いずれも名前の昇順で並べる", () => {
    const rows = buildTreeRows(ITEMS, options());
    expect(rows.map((row) => `${row.depth}:${row.kind}:${row.name}`)).toEqual([
      "0:folder:src",
      "1:folder:cobol",
      "2:file:SYK001.cbl",
      "2:file:SYK002.cbl",
      "1:folder:copy",
      "2:file:SYKCPY1.cpy",
      "1:file:MAP1.bms",
      "0:file:JOB1.jcl",
    ]);
  });

  it("畳んだフォルダの中身は並べない", () => {
    const rows = buildTreeRows(ITEMS, options({ collapsed: new Set(["src/cobol"]) }));
    expect(rows.some((row) => row.name === "SYK001.cbl")).toBe(false);
    expect(rows.find((row) => row.path === "src/cobol")?.expanded).toBe(false);
  });

  it("種別で絞ると、残らないフォルダごと消える", () => {
    const rows = buildTreeRows(ITEMS, options({ type: "jcl" }));
    expect(rows.map((row) => row.name)).toEqual(["JOB1.jcl"]);
  });

  it("名前フィルタは相対パスへ当て、大小を問わない", () => {
    const rows = buildTreeRows(ITEMS, options({ search: "cpy" }));
    expect(rows.map((row) => row.name)).toEqual(["src", "copy", "SYKCPY1.cpy"]);
  });

  it("資産の行へ種別と指摘件数を添える", () => {
    const rows = buildTreeRows(ITEMS, options({ findingCounts: { "JOB1.jcl": 3 } }));
    const jcl = rows.find((row) => row.name === "JOB1.jcl");
    expect(jcl?.type).toBe("jcl");
    expect(jcl?.findingCount).toBe(3);
    expect(jcl?.item?.path).toBe("JOB1.jcl");
  });

  it("該当が無ければ 1 行も返さない", () => {
    expect(buildTreeRows(ITEMS, options({ search: "見つからない" }))).toEqual([]);
  });
});

describe("countFindingsByFile(資産ごとの指摘件数)", () => {
  it("複数の SARIF をまたいで数える", () => {
    const counts = countFindingsByFile(
      [
        { ruleId: "R001", level: "warning", message: "", file: "a.cbl", startLine: 1, startColumn: 1 },
        { ruleId: "R002", level: "warning", message: "", file: "a.cbl", startLine: 2, startColumn: 1 },
      ],
      [{ ruleId: "S001", level: "note", message: "", file: "b.cbl", startLine: 3, startColumn: 1 }],
    );
    expect(counts).toEqual({ "a.cbl": 2, "b.cbl": 1 });
  });
});

describe("toggleCollapsed(フォルダの開閉)", () => {
  it("開いていれば畳み、畳んでいれば開く", () => {
    const closed = toggleCollapsed(new Set(), "src");
    expect(closed.has("src")).toBe(true);
    expect(toggleCollapsed(closed, "src").has("src")).toBe(false);
  });
});
