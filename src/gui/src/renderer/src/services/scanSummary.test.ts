import { describe, it, expect } from "vitest";
import { readScanDiscovery, scanNotices } from "./scanSummary";

describe("readScanDiscovery(scan サマリの走査情報)", () => {
  it("engine が返した値をそのまま取り込む", () => {
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

describe("scanNotices(走査の警告)", () => {
  const EMPTY = { undecided: [], mismatches: [], truncated: false, unreadable: [] };

  it("走査情報が無ければ警告を出さない", () => {
    expect(scanNotices(null, 12)).toEqual([]);
  });

  it("取りこぼしが無く資産もあれば警告を出さない", () => {
    expect(scanNotices(EMPTY, 12)).toEqual([]);
  });

  it("資産が 0 件なら、判定の手掛かりを添えて知らせる", () => {
    const notices = scanNotices(EMPTY, 0);
    expect(notices).toHaveLength(1);
    expect(notices[0].text).toContain("1 件も見つかりませんでした");
  });

  it("落としたもの・解釈を変えたもの・打ち切りをこの順に並べる", () => {
    const notices = scanNotices(
      {
        undecided: ["misc/README.txt"],
        unreadable: ["cobol/LOCKED.cbl"],
        mismatches: [{ path: "copy/A.cpy", byExtension: "COPYBOOK", byContent: "COBOL" }],
        truncated: true,
      },
      12,
    );
    expect(notices.map((notice) => notice.details)).toEqual([
      ["misc/README.txt"],
      ["cobol/LOCKED.cbl"],
      ["copy/A.cpy → COBOL 本体"],
      [],
    ]);
  });
});
