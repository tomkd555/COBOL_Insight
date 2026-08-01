import { describe, it, expect, vi } from "vitest";
import { checkDirectoryExists, type DirectoryStat } from "./pathCheck";

describe("checkDirectoryExists", () => {
  it("ディレクトリとして存在すれば true を返す", async () => {
    const fs: DirectoryStat = {
      stat: vi.fn().mockResolvedValue({ isDirectory: () => true }),
    };
    await expect(checkDirectoryExists(fs, "C:\\資産\\copybook")).resolves.toBe(true);
    expect(fs.stat).toHaveBeenCalledWith("C:\\資産\\copybook");
  });

  it("パスが無ければ false を返す(stat が拒否される)", async () => {
    const fs: DirectoryStat = {
      stat: vi.fn().mockRejectedValue(Object.assign(new Error("ENOENT"), { code: "ENOENT" })),
    };
    await expect(checkDirectoryExists(fs, "C:\\無い\\パス")).resolves.toBe(false);
  });

  it("そのパスがディレクトリでなくファイルなら false を返す", async () => {
    const fs: DirectoryStat = {
      stat: vi.fn().mockResolvedValue({ isDirectory: () => false }),
    };
    await expect(checkDirectoryExists(fs, "C:\\資産\\copybook.txt")).resolves.toBe(false);
  });
});
