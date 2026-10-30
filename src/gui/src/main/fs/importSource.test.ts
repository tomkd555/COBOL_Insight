import { describe, expect, it } from "vitest";
import { resolve } from "node:path";
import { importSource, type ImportFileSystem } from "./importSource";

/** The asset folder C:/assets is there, with the files and folders named. */
function memory(existing: string[] = []): ImportFileSystem & { written: Map<string, string> } {
  const written = new Map<string, string>();
  const present = new Set(existing.map((path) => resolve(path)));
  const assets = resolve("C:/assets");
  return {
    written,
    makeDir: async () => undefined,
    exists: async (absPath) => resolve(absPath) === assets || present.has(resolve(absPath)),
    entry: async (absPath) => (present.has(resolve(absPath)) ? "file" : null),
    realPath: async (absPath) => absPath,
    writeText: async (absPath, text) => {
      written.set(resolve(absPath), text);
      return true;
    },
  };
}

describe("importSource", () => {
  // An absolute path on the platform the tests run on.
  const base = { inputDir: resolve("C:/assets"), destDir: "cobol", overwrite: false };

  it("writes the lines with CRLF endings and a trailing newline", async () => {
    const fs = memory();
    const result = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE", "TWO"] });
    expect(result).toEqual({ status: "written", relPath: "cobol/A.cbl", lineCount: 2 });
    expect(fs.written.get(resolve("C:/assets/cobol/A.cbl"))).toBe("ONE\r\nTWO\r\n");
  });

  it("refuses to overwrite unless allowed", async () => {
    const fs = memory(["C:/assets/cobol/A.cbl"]);
    const blocked = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] });
    expect(blocked).toEqual({ status: "exists", relPath: "cobol/A.cbl", lineCount: 0 });
    expect(fs.written.size).toBe(0);

    const allowed = await importSource(fs, {
      ...base,
      fileName: "A.cbl",
      lines: ["ONE"],
      overwrite: true,
    });
    expect(allowed.status).toBe("written");
  });

  it("refuses a destination that climbs out with .. by the name rule", async () => {
    await expect(
      importSource(memory(), { ...base, destDir: "..", fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/保存先「\.\.\/A\.cbl」は使えません/);
  });

  it("refuses a destination folder that is a link outside the asset folder", async () => {
    const fs = memory();
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? "C:/elsewhere" : absPath;
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/資産フォルダの外/);
  });

  it("names the destination when it resolves outside the asset folder", async () => {
    const fs = memory(["C:/assets/cobol"]);
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? resolve("C:/elsewhere/cobol") : absPath;
    await expect(importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] })).rejects.toThrow(
      new Error("「cobol/A.cbl」は資産フォルダの外です。資産フォルダの中の保存先を指定してください。"),
    );
  });

  it("writes nothing when the destination resolves outside the asset folder", async () => {
    const fs = memory(["C:/assets/cobol"]);
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? resolve("C:/elsewhere/cobol") : absPath;
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/資産フォルダの外/);
    expect(fs.written.size).toBe(0);
  });

  it("refuses a file name that is a link, without writing", async () => {
    const fs = memory(["C:/assets/cobol/A.cbl"]);
    fs.entry = async (absPath) => (resolve(absPath) === resolve("C:/assets/cobol/A.cbl") ? "link" : null);
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"], overwrite: true }),
    ).rejects.toThrow(/「cobol\/A\.cbl」はリンクです/);
    expect(fs.written.size).toBe(0);
  });

  it("creates no folder when the nearest existing ancestor leads outside", async () => {
    const fs = memory(["C:/assets/cobol"]);
    const made: string[] = [];
    fs.makeDir = async (absPath) => {
      made.push(absPath);
    };
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? "C:/elsewhere" : absPath;
    await expect(
      importSource(fs, { ...base, destDir: "cobol/new/deeper", fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(/資産フォルダの外/);
    expect(made).toEqual([]);
    expect(fs.written.size).toBe(0);
  });

  it("allows a file directly in the asset folder", async () => {
    const fs = memory();
    const result = await importSource(fs, {
      ...base,
      destDir: "",
      fileName: "A.cbl",
      lines: ["ONE"],
    });
    expect(result.relPath).toBe("A.cbl");
  });

  it("refuses a file name that is a folder, without writing", async () => {
    const fs = memory();
    fs.entry = async () => "folder";
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"], overwrite: true }),
    ).rejects.toThrow(/「cobol\/A\.cbl」はフォルダです/);
    expect(fs.written.size).toBe(0);
  });

  it("reports a failed write with the relative path and the filesystem's code", async () => {
    const fs = memory();
    fs.realPath = async () => {
      throw Object.assign(new Error("EIO: i/o error, realpath 'C:\\assets'"), {
        code: "EIO",
      });
    };
    await expect(
      importSource(fs, { ...base, destDir: "new", fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow("「new/A.cbl」を保存できませんでした（EIO）。保存先のフォルダを確かめてください。");
  });

  it("writes over a file under the name the folder holds, whatever case was typed", async () => {
    const fs = memory(["C:/assets/cobol/LOW.cbl"]);
    fs.realPath = async (absPath) => absPath.replace(/LOW\.cbl$/, "low.cbl");
    const result = await importSource(fs, {
      ...base,
      fileName: "LOW.cbl",
      lines: ["ONE"],
      overwrite: true,
    });
    expect(result.relPath).toBe("cobol/low.cbl");
    expect([...fs.written.keys()]).toEqual([resolve("C:/assets/cobol/low.cbl")]);
  });

  it("refuses an import with no lines", async () => {
    await expect(
      importSource(memory(), { ...base, fileName: "A.cbl", lines: [] }),
    ).rejects.toThrow(/貼り付けた本文がありません/);
  });

  const FAILED = "「cobol/A.cbl」を保存できませんでした";

  /** The filesystem refuses the write itself with the given code. */
  function refusing(code: string | undefined): ImportFileSystem & { written: Map<string, string> } {
    const fs = memory();
    fs.writeText = async () => {
      throw Object.assign(new Error("native message that names the full path"), { code });
    };
    return fs;
  }

  it.each([
    ["EPERM", "ファイルが読み取り専用か、ほかのプログラムが開いています。"],
    ["EBUSY", "ファイルが読み取り専用か、ほかのプログラムが開いています。"],
    ["ENAMETOOLONG", "名前を短くしてください。"],
    ["ENOSPC", "保存先の空き容量を確かめてください。"],
    ["EEXIST", "保存先フォルダの途中に同じ名前のファイルがあります。"],
    ["ENOENT", "保存先のフォルダが見つからないか、作成できませんでした。保存先フォルダを確かめてください。"],
  ])("words the filesystem's %s in a sentence of its own", async (code, sentence) => {
    await expect(
      importSource(refusing(code), { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(`${FAILED}。${sentence}`);
  });

  it("keeps an unknown code in parentheses", async () => {
    await expect(
      importSource(refusing("EWEIRD"), { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(`${FAILED}（EWEIRD）。保存先のフォルダを確かめてください。`);
  });

  it("reports an error that carries no code without parentheses", async () => {
    await expect(
      importSource(refusing(undefined), { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(`${FAILED}。保存先のフォルダを確かめてください。`);
  });

  it("reports an entry check that throws EACCES as a failed write", async () => {
    const fs = memory();
    fs.entry = async () => {
      throw Object.assign(new Error("EACCES: permission denied"), { code: "EACCES" });
    };
    await expect(
      importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(`${FAILED}。ファイルが読み取り専用か、ほかのプログラムが開いています。`);
    expect(fs.written.size).toBe(0);
  });

  describe("a request whose members have the wrong type", () => {
    const RECEIVED = "貼り付けの内容を受け取れませんでした。もう一度貼り付けてください。";
    const lines = ["ONE"];

    it.each([
      ["a file name that is a number", { ...base, fileName: 5, lines }],
      ["an asset folder that is missing", { destDir: "cobol", overwrite: false, fileName: "A.cbl", lines }],
      ["a destination that is null", { ...base, destDir: null, fileName: "A.cbl", lines }],
      ["lines given as one string", { ...base, fileName: "A.cbl", lines: "ONE" }],
      ["a line that is a number", { ...base, fileName: "A.cbl", lines: ["ONE", 2] }],
      ["an overwrite that is missing", { inputDir: base.inputDir, destDir: "cobol", fileName: "A.cbl", lines }],
      ["an asset folder that is empty", { ...base, inputDir: "", fileName: "A.cbl", lines }],
      ["an asset folder that is a relative path", { ...base, inputDir: "assets", fileName: "A.cbl", lines }],
      ["an overwrite given as text", { ...base, overwrite: "true", fileName: "A.cbl", lines }],
      ["no request at all", null],
    ])("is refused with the Japanese sentence for %s", async (_title, request) => {
      const fs = memory();
      await expect(importSource(fs, request as never)).rejects.toThrow(RECEIVED);
      expect(fs.written.size).toBe(0);
    });
  });

  it("refuses a missing asset folder before any folder is created", async () => {
    const fs = memory();
    fs.exists = async () => false;
    const made: string[] = [];
    fs.makeDir = async (absPath) => {
      made.push(absPath);
    };
    await expect(
      importSource(fs, { ...base, destDir: "cobol/new", fileName: "A.cbl", lines: ["ONE"] }),
    ).rejects.toThrow(
      new Error(
        "「cobol/new/A.cbl」を保存できませんでした。資産フォルダが見つかりません。資産フォルダの場所を確かめて、開き直してください。",
      ),
    );
    expect(made).toEqual([]);
    expect(fs.written.size).toBe(0);
  });

  it("words ENOENT from the filesystem without the code", async () => {
    const fs = memory();
    fs.makeDir = async () => {
      throw Object.assign(new Error("ENOENT: no such file or directory, mkdir 'C:\\assets\\cobol'"), { code: "ENOENT" });
    };
    await expect(importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] })).rejects.toThrow(
      new Error(
        "「cobol/A.cbl」を保存できませんでした。保存先のフォルダが見つからないか、作成できませんでした。保存先フォルダを確かめてください。",
      ),
    );
  });

  it("names the file as the folder holds it when it answers exists", async () => {
    const fs = memory(["C:/assets/cobol/LOW.cbl"]);
    fs.realPath = async (absPath) => absPath.replace(/LOW\.cbl$/, "low.cbl");
    const result = await importSource(fs, { ...base, fileName: "LOW.cbl", lines: ["ONE"] });
    expect(result).toEqual({ status: "exists", relPath: "cobol/low.cbl", lineCount: 0 });
    expect(fs.written.size).toBe(0);
  });

  it("answers exists without writing when writeText finds the name taken", async () => {
    const fs = memory();
    fs.writeText = async () => false;
    const result = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] });
    expect(result).toEqual({ status: "exists", relPath: "cobol/A.cbl", lineCount: 0 });
    expect(fs.written.size).toBe(0);
  });

  it("writes the typed name when the file's real path lies in another folder", async () => {
    const fs = memory(["C:/assets/cobol/A.cbl"]);
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol/A.cbl") ? "C:/assets/elsewhere/Z.cbl" : absPath;
    const result = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"], overwrite: true });
    expect(result).toEqual({ status: "written", relPath: "cobol/A.cbl", lineCount: 1 });
    expect([...fs.written.keys()]).toEqual([resolve("C:/assets/cobol/A.cbl")]);
  });

  it("accepts a destination folder whose real name is ..x inside the asset folder", async () => {
    // A destDir of "..x" is refused by the name rule, so the folder is reached through realPath.
    const fs = memory();
    fs.realPath = async (absPath) =>
      absPath.replace(/\\/g, "/").endsWith("/cobol") ? "C:/assets/..x" : absPath;
    const result = await importSource(fs, { ...base, fileName: "A.cbl", lines: ["ONE"] });
    expect(result).toEqual({ status: "written", relPath: "..x/A.cbl", lineCount: 1 });
    expect([...fs.written.keys()]).toEqual([resolve("C:/assets/..x/A.cbl")]);
  });
});
