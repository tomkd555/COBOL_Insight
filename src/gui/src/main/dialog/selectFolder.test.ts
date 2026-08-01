import { describe, it, expect, vi } from "vitest";
import { selectInputFolder, type OpenDirectoryDialog } from "./selectFolder";

describe("selectInputFolder", () => {
  it("選ばれたフォルダの絶対パスを返す", async () => {
    const dialog: OpenDirectoryDialog = vi
      .fn()
      .mockResolvedValue({ canceled: false, filePaths: ["C:\\資産\\SYK"] });
    await expect(selectInputFolder(dialog)).resolves.toBe("C:\\資産\\SYK");
  });

  it("キャンセルなら null を返す", async () => {
    const dialog: OpenDirectoryDialog = vi.fn().mockResolvedValue({ canceled: true, filePaths: [] });
    await expect(selectInputFolder(dialog)).resolves.toBeNull();
  });

  it("キャンセルでなくても選択が空なら null を返す", async () => {
    const dialog: OpenDirectoryDialog = vi.fn().mockResolvedValue({ canceled: false, filePaths: [] });
    await expect(selectInputFolder(dialog)).resolves.toBeNull();
  });

  it("複数選択された場合は先頭のみ採る", async () => {
    const dialog: OpenDirectoryDialog = vi
      .fn()
      .mockResolvedValue({ canceled: false, filePaths: ["C:\\a", "C:\\b"] });
    await expect(selectInputFolder(dialog)).resolves.toBe("C:\\a");
  });
});
