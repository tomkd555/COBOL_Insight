import { describe, expect, it } from "vitest";
import { closeDecision } from "./closeGuard";
import { compareSeverity, severityOf, visibleSeverities } from "./severity";
import { codepageLabel, isEbcdic, normalizeCodepage } from "../../../shared/codepage";

describe("severityOf", () => {
  it("maps the engine's vocabulary onto the interface's", () => {
    expect(severityOf("HIGH")).toBe("high");
    expect(severityOf("medium")).toBe("medium");
    expect(severityOf("LOW")).toBe("low");
    expect(severityOf("ADVISORY")).toBe("warning");
  });

  it("treats anything unknown as medium rather than dropping the finding", () => {
    expect(severityOf("CRITICAL")).toBe("medium");
    expect(severityOf("")).toBe("medium");
  });
});

describe("visibleSeverities", () => {
  it("returns everything at or above the threshold, in order", () => {
    expect(visibleSeverities("warning")).toEqual(["high", "medium", "low", "warning"]);
    expect(visibleSeverities("medium")).toEqual(["high", "medium"]);
    expect(visibleSeverities("high")).toEqual(["high"]);
  });
});

describe("compareSeverity", () => {
  it("sorts the most severe first", () => {
    expect([...(["low", "high", "medium"] as const)].sort(compareSeverity)).toEqual([
      "high",
      "medium",
      "low",
    ]);
  });
});

describe("closeDecision", () => {
  it("closes a clean tab outright and confirms a dirty one", () => {
    expect(closeDecision(false)).toBe("close");
    expect(closeDecision(true)).toBe("confirm");
  });
});

describe("normalizeCodepage", () => {
  it("folds the aliases of each codepage onto one name", () => {
    expect(normalizeCodepage("shift-jis")).toBe("Shift_JIS");
    expect(normalizeCodepage("MS932")).toBe("Shift_JIS");
    expect(normalizeCodepage("windows-31j")).toBe("Shift_JIS");
    expect(normalizeCodepage("utf8")).toBe("UTF-8");
    expect(normalizeCodepage("cp930")).toBe("IBM930");
    expect(normalizeCodepage("x-IBM939")).toBe("IBM939");
  });

  it("returns null for an unknown name and for no name at all", () => {
    expect(normalizeCodepage("EUC-JP")).toBeNull();
    expect(normalizeCodepage(null)).toBeNull();
    expect(normalizeCodepage(undefined)).toBeNull();
  });
});

describe("codepageLabel", () => {
  it("shows the display name of a known codepage", () => {
    expect(codepageLabel("cp930", "不明")).toBe("EBCDIC CP930");
  });

  it("shows an unknown value as recorded, and the fallback when there is none", () => {
    expect(codepageLabel("EUC-JP", "不明")).toBe("EUC-JP");
    expect(codepageLabel(null, "不明")).toBe("不明");
    expect(codepageLabel("", "不明")).toBe("不明");
  });
});

describe("isEbcdic", () => {
  it("is true only for the two EBCDIC codepages", () => {
    expect(isEbcdic("IBM930")).toBe(true);
    expect(isEbcdic("cp939")).toBe(true);
    expect(isEbcdic("Shift_JIS")).toBe(false);
    expect(isEbcdic(null)).toBe(false);
  });
});
