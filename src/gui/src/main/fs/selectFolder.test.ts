import { describe, expect, it } from "vitest";
import { dirExists, selectFolder } from "./selectFolder";

describe("selectFolder", () => {
  it("returns the chosen folder", async () => {
    await expect(
      selectFolder(async () => ({ canceled: false, filePaths: ["C:/assets"] })),
    ).resolves.toBe("C:/assets");
  });

  it("returns null when the dialog was cancelled or nothing was chosen", async () => {
    await expect(
      selectFolder(async () => ({ canceled: true, filePaths: ["C:/assets"] })),
    ).resolves.toBeNull();
    await expect(selectFolder(async () => ({ canceled: false, filePaths: [] }))).resolves.toBeNull();
  });
});

describe("dirExists", () => {
  it("is true only for a directory", async () => {
    await expect(dirExists({ stat: async () => ({ isDirectory: () => true }) }, "C:/a")).resolves.toBe(
      true,
    );
    await expect(
      dirExists({ stat: async () => ({ isDirectory: () => false }) }, "C:/a.txt"),
    ).resolves.toBe(false);
  });

  it("is false when the path cannot be reached at all", async () => {
    await expect(
      dirExists(
        {
          stat: async () => {
            throw new Error("ENOENT");
          },
        },
        "C:/missing",
      ),
    ).resolves.toBe(false);
  });
});
