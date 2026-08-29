import { describe, expect, it } from "vitest";
import { resolve } from "node:path";
import { resolveSourceFile, resolveWithinBase, type RealPathResolver } from "./pathGuard";

const BASE = "C:/assets";

/** A resolver that maps the paths it is told about and otherwise returns the path unchanged. */
function resolver(links: Record<string, string> = {}): RealPathResolver {
  return {
    realPath: async (absPath) => links[absPath.replace(/\\/g, "/")] ?? absPath,
  };
}

describe("resolveWithinBase", () => {
  it("resolves a relative path against the base", () => {
    expect(resolveWithinBase(BASE, "cobol/A.cbl")).toBe(resolve(BASE, "cobol/A.cbl"));
  });

  it("accepts an absolute path that is already inside the base", () => {
    expect(resolveWithinBase(BASE, "C:/assets/cobol/A.cbl")).toBe(resolve(BASE, "cobol/A.cbl"));
  });

  it("rejects a relative path that climbs out", () => {
    expect(resolveWithinBase(BASE, "../secrets.txt")).toBeNull();
    expect(resolveWithinBase(BASE, "cobol/../../secrets.txt")).toBeNull();
  });

  it("rejects an absolute path in another folder", () => {
    expect(resolveWithinBase(BASE, "C:/Windows/System32/config")).toBeNull();
  });

  it("rejects a sibling folder whose name merely starts with the base name", () => {
    expect(resolveWithinBase(BASE, "C:/assets-other/A.cbl")).toBeNull();
  });

  it("rejects the base directory itself, which is not a file", () => {
    expect(resolveWithinBase(BASE, "")).toBeNull();
    expect(resolveWithinBase(BASE, ".")).toBeNull();
    expect(resolveWithinBase(BASE, "C:/assets")).toBeNull();
  });

  it("keeps Windows reserved device names inside the base rather than treating them as devices", () => {
    // CON, NUL and friends are only devices when used bare; as a path segment they stay contained.
    for (const name of ["CON", "NUL", "PRN", "AUX", "COM1", "LPT1"]) {
      expect(resolveWithinBase(BASE, name)).toBe(resolve(BASE, name));
    }
  });

  it("rejects an absolute bare device name", () => {
    expect(resolveWithinBase(BASE, "\\\\.\\NUL")).toBeNull();
  });

  it("rejects a UNC path", () => {
    expect(resolveWithinBase(BASE, "\\\\server\\share\\A.cbl")).toBeNull();
  });
});

describe("resolveSourceFile", () => {
  it("returns the real path of a file inside the base", async () => {
    await expect(resolveSourceFile(resolver(), BASE, "cobol/A.cbl")).resolves.toBe(
      resolve(BASE, "cobol/A.cbl"),
    );
  });

  it("rejects a literal path outside the base before touching the filesystem", async () => {
    await expect(resolveSourceFile(resolver(), BASE, "../secrets.txt")).rejects.toThrow(
      /outside the asset folder/,
    );
  });

  it("rejects a symlink inside the base that points outside it", async () => {
    const link = resolve(BASE, "link.cbl").replace(/\\/g, "/");
    await expect(
      resolveSourceFile(resolver({ [link]: "C:/elsewhere/secret.cbl" }), BASE, "link.cbl"),
    ).rejects.toThrow(/outside the asset folder/);
  });

  it("still accepts a file when the base itself is a junction", async () => {
    const base = "C:/link-to-assets";
    const real = "C:/real/assets";
    const target = resolve(base, "A.cbl").replace(/\\/g, "/");
    await expect(
      resolveSourceFile(
        resolver({ [base]: real, [target]: resolve(real, "A.cbl") }),
        base,
        "A.cbl",
      ),
    ).resolves.toBe(resolve(real, "A.cbl"));
  });
});
