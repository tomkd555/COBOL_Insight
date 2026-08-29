import { describe, expect, it } from "vitest";
import { resolvePortableUserData } from "./portable";

describe("resolvePortableUserData", () => {
  it("leaves Electron's default in charge during a development run", () => {
    expect(
      resolvePortableUserData({
        isPackaged: false,
        exePath: "C:/app/COBOL Insight.exe",
        ensureWritable: () => true,
      }),
    ).toBeNull();
  });

  it("points at data/ beside the executable in a distribution", () => {
    expect(
      resolvePortableUserData({
        isPackaged: true,
        exePath: "C:/app/COBOL Insight.exe",
        ensureWritable: () => true,
      }),
    ).toBe("C:\\app\\data");
  });

  it("falls back to Electron's default when data/ cannot be created", () => {
    expect(
      resolvePortableUserData({
        isPackaged: true,
        exePath: "//share/app/COBOL Insight.exe",
        ensureWritable: () => false,
      }),
    ).toBeNull();
  });
});
