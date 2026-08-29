import { describe, expect, it } from "vitest";
import type { AssetInventoryItem, SarifFinding } from "../../../shared/ipc";
import {
  assetTypeOf,
  buildTreeRows,
  countFindingsByFile,
  toggleCollapsed,
  type TreeOptions,
} from "./assetTree";

function item(path: string, type = "PROGRAM", codepage: string | null = "Shift_JIS"): AssetInventoryItem {
  return {
    id: path.length,
    path,
    name: path.split("/").pop() ?? path,
    type,
    codepage,
    byteSize: 100,
    findingCount: 0,
  };
}

const ITEMS: AssetInventoryItem[] = [
  item("cobol/SYK002.cbl"),
  item("cobol/SYK001.cbl"),
  item("cobol/sub/SYK009.cbl"),
  item("copybook/SYKCPY1.cpy", "COPYBOOK"),
  item("jcl/SYKD010.jcl", "JCL"),
  item("bms/SYKMAP1.bms", "BMS"),
  item("misc/notes.txt", "UNKNOWN", null),
];

const OPTIONS: TreeOptions = {
  search: "",
  type: "all",
  collapsed: new Set(),
  findingCounts: {},
};

describe("assetTypeOf", () => {
  it("maps the engine node types and puts everything else in other", () => {
    expect(assetTypeOf("PROGRAM")).toBe("cobol");
    expect(assetTypeOf("COPYBOOK")).toBe("copybook");
    expect(assetTypeOf("JCL")).toBe("jcl");
    expect(assetTypeOf("BMS")).toBe("bms");
    expect(assetTypeOf("DATASET")).toBe("other");
    expect(assetTypeOf("UNKNOWN")).toBe("other");
  });
});

describe("buildTreeRows", () => {
  it("puts folders before files and sorts each by name", () => {
    const rows = buildTreeRows(ITEMS, OPTIONS);
    expect(rows.map((row) => `${row.kind}:${row.name}`).slice(0, 4)).toEqual([
      "folder:bms",
      "file:SYKMAP1.bms",
      "folder:cobol",
      "folder:sub",
    ]);
  });

  it("gives each row the depth of its place in the hierarchy", () => {
    const rows = buildTreeRows(ITEMS, OPTIONS);
    const nested = rows.find((row) => row.name === "SYK009.cbl");
    expect(nested?.depth).toBe(2);
  });

  it("does not list the contents of a collapsed folder", () => {
    const rows = buildTreeRows(ITEMS, { ...OPTIONS, collapsed: new Set(["cobol"]) });
    expect(rows.some((row) => row.name === "SYK001.cbl")).toBe(false);
    expect(rows.find((row) => row.name === "cobol")?.expanded).toBe(false);
  });

  it("filters by kind and leaves out the folders that empty", () => {
    const rows = buildTreeRows(ITEMS, { ...OPTIONS, type: "jcl" });
    expect(rows.map((row) => row.name)).toEqual(["jcl", "SYKD010.jcl"]);
  });

  it("matches the search against the whole path, ignoring case", () => {
    expect(buildTreeRows(ITEMS, { ...OPTIONS, search: "syk001" }).map((row) => row.name)).toEqual([
      "cobol",
      "SYK001.cbl",
    ]);
    expect(buildTreeRows(ITEMS, { ...OPTIONS, search: "copybook/" }).map((row) => row.name)).toEqual([
      "copybook",
      "SYKCPY1.cpy",
    ]);
  });

  it("returns nothing when the filters exclude everything", () => {
    expect(buildTreeRows(ITEMS, { ...OPTIONS, search: "nothing" })).toEqual([]);
  });

  it("attaches the kind, the inventory item and the finding count to a file row", () => {
    const rows = buildTreeRows(ITEMS, {
      ...OPTIONS,
      findingCounts: { "cobol/SYK001.cbl": 3 },
    });
    const row = rows.find((candidate) => candidate.name === "SYK001.cbl");
    expect(row).toMatchObject({ type: "cobol", findingCount: 3 });
    expect(row?.item?.path).toBe("cobol/SYK001.cbl");
  });

  it("keeps an asset whose codepage could not be detected, with codepage null", () => {
    const row = buildTreeRows(ITEMS, OPTIONS).find((candidate) => candidate.name === "notes.txt");
    expect(row?.item?.codepage).toBeNull();
    expect(row?.type).toBe("other");
  });
});

describe("countFindingsByFile", () => {
  const finding = (file: string): SarifFinding => ({
    ruleId: "R001",
    level: "warning",
    message: "m",
    file,
    startLine: 1,
    startColumn: 1,
  });

  it("adds the counts across every group it is given", () => {
    expect(
      countFindingsByFile([finding("a.cbl"), finding("a.cbl")], [finding("a.cbl"), finding("b.cbl")]),
    ).toEqual({ "a.cbl": 3, "b.cbl": 1 });
  });

  it("counts nothing when there are no findings", () => {
    expect(countFindingsByFile([], [])).toEqual({});
  });
});

describe("toggleCollapsed", () => {
  it("flips one folder and leaves the original set alone", () => {
    const original = new Set(["a"]);
    expect([...toggleCollapsed(original, "b")]).toEqual(["a", "b"]);
    expect([...toggleCollapsed(original, "a")]).toEqual([]);
    expect([...original]).toEqual(["a"]);
  });
});
