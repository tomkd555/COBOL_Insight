import { describe, it, expect } from "vitest";
import { readScanDiscovery } from "./scanSummary";

describe("readScanDiscovery(scan サマリの走査情報)", () => {
  it("engine が返した値をそのまま写す", () => {
    const discovery = readScanDiscovery({
      undecided: ["misc/README.txt"],
      mismatches: [{ path: "copy/SYK001.cpy", byExtension: "COPYBOOK", byContent: "COBOL" }],
      truncated: true,
      unreadable: ["cobol/LOCKED.cbl"],
    });
    expect(discovery).toEqual({
      undecided: ["misc/README.txt"],
      mismatches: [{ path: "copy/SYK001.cpy", byExtension: "COPYBOOK", byContent: "COBOL" }],
      truncated: true,
      unreadable: ["cobol/LOCKED.cbl"],
    });
  });

  it("サマリが無ければ取りこぼし無しとして扱う", () => {
    expect(readScanDiscovery(null)).toEqual({
      undecided: [],
      mismatches: [],
      truncated: false,
      unreadable: [],
    });
  });

  it("項目が無ければ取りこぼし無し側へ倒す(古い engine の互換)", () => {
    expect(readScanDiscovery({})).toEqual({
      undecided: [],
      mismatches: [],
      truncated: false,
      unreadable: [],
    });
  });

  it("型の合わない値は個別に読み飛ばす", () => {
    const discovery = readScanDiscovery({
      undecided: ["a/A.cbl", 7, null],
      mismatches: [
        { path: "copy/SYK001.cpy", byExtension: "COPYBOOK", byContent: "COBOL" },
        { path: "copy/BAD.cpy", byExtension: "COPYBOOK", byContent: "不明" },
        { path: 42, byExtension: "COPYBOOK", byContent: "COBOL" },
        "not-an-object",
      ],
      truncated: "yes",
      unreadable: "not-an-array",
    });
    expect(discovery.undecided).toEqual(["a/A.cbl"]);
    expect(discovery.mismatches).toEqual([
      { path: "copy/SYK001.cpy", byExtension: "COPYBOOK", byContent: "COBOL" },
    ]);
    expect(discovery.truncated).toBe(false);
    expect(discovery.unreadable).toEqual([]);
  });
});
