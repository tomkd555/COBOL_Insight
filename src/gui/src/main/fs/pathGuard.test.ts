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

  it("accepts a file under a folder whose name begins with two dots", () => {
    expect(resolveWithinBase(BASE, "..x/A.cbl")).toBe(resolve(BASE, "..x/A.cbl"));
    expect(resolveWithinBase(BASE, "cobol/..y/A.cbl")).toBe(resolve(BASE, "cobol/..y/A.cbl"));
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

  it("rejects the base directory itself, since a target is a file inside it", () => {
    expect(resolveWithinBase(BASE, "")).toBeNull();
    expect(resolveWithinBase(BASE, ".")).toBeNull();
    expect(resolveWithinBase(BASE, "C:/assets")).toBeNull();
  });

  it("resolves a Windows device name to a path inside the base", () => {
    // The boundary is all this gate decides. Whether a file may be written under such a name is
    // the import rule's to refuse (shared/importPath.ts).
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
    const touched: string[] = [];
    const recording: RealPathResolver = {
      realPath: async (absPath) => {
        touched.push(absPath);
        return absPath;
      },
    };
    await expect(resolveSourceFile(recording, BASE, "../secrets.txt")).rejects.toThrow(
      /資産フォルダの外/,
    );
    expect(touched).toEqual([]);
  });

  it("rejects a symlink inside the base that points outside it", async () => {
    const link = resolve(BASE, "link.cbl").replace(/\\/g, "/");
    await expect(
      resolveSourceFile(resolver({ [link]: "C:/elsewhere/secret.cbl" }), BASE, "link.cbl"),
    ).rejects.toThrow(/資産フォルダの外/);
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
