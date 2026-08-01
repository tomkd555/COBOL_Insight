import { describe, it, expect } from "vitest";
import { readScanDiscovery } from "./scanSummary";

describe("readScanDiscovery(scan サマリの走査情報)", () => {
  it("engine が返した値をそのまま写す", () => {
    const discovery = readScanDiscovery({
      discoveryMode: "recursive",
      truncated: true,
      outsideConventionCount: 3,
      outsideConventionSamples: ["a/A.cbl", "b/B.cpy"],
    });
    expect(discovery).toEqual({
      mode: "recursive",
      truncated: true,
      outsideCount: 3,
      outsideSamples: ["a/A.cbl", "b/B.cpy"],
    });
  });

  it("サマリが無ければ従来構成で取りこぼし無しとして扱う", () => {
    expect(readScanDiscovery(null)).toEqual({
      mode: "convention",
      truncated: false,
      outsideCount: 0,
      outsideSamples: [],
    });
  });

  it("型の合わない値は既定値へ倒す", () => {
    const discovery = readScanDiscovery({
      discoveryMode: 42,
      truncated: "yes",
      outsideConventionCount: "3",
      outsideConventionSamples: ["a/A.cbl", 7, null],
    });
    expect(discovery.mode).toBe("convention");
    expect(discovery.truncated).toBe(false);
    expect(discovery.outsideCount).toBe(0);
    expect(discovery.outsideSamples).toEqual(["a/A.cbl"]);
  });
});
