import { describe, expect, it } from "vitest";
import type { SaveRequest } from "../../shared/ipc";
import { parseSaveSummary, saveSource, type SaveDeps, type SaveFileSystem } from "./save";

function deps(summary: Record<string, unknown> | null, log: { seen: SaveRequest[]; written: string[]; removed: string[] }): SaveDeps {
  const fs: SaveFileSystem = {
    realPath: async (absPath) => absPath,
    writeText: async (absPath, text) => {
      log.written.push(`${absPath}:${text}`);
    },
    remove: async (absPath) => {
      log.removed.push(absPath);
    },
  };
  return {
    fs,
    tempFile: () => "C:/data/edited.tmp",
    dbPath: "C:/data/p.db",
    run: async (request) => {
      log.seen.push(request);
      return {
        subcommand: "save",
        exitCode: 0,
        summary,
        stdout: "",
        stderr: "",
        outputs: {},
      };
    },
  };
}

function emptyLog(): { seen: SaveRequest[]; written: string[]; removed: string[] } {
  return { seen: [], written: [], removed: [] };
}

describe("parseSaveSummary", () => {
  it("reads the written flag, the changed range and the reparse errors", () => {
    const result = parseSaveSummary(
      {
        written: true,
        path: "C:/assets/a.cbl",
        changedLineFrom: 10,
        changedLineTo: 12,
        reparseErrors: [{ line: 11, message: "unexpected token" }],
        error: "",
        exitCode: 1,
      },
      0,
    );
    expect(result.written).toBe(true);
    expect(result.changedLineTo).toBe(12);
    expect(result.reparseErrors).toEqual([{ line: 11, message: "unexpected token" }]);
    // The summary's own exit code wins over the process's.
    expect(result.exitCode).toBe(1);
  });

  it("falls back to the process exit code when the summary omits it", () => {
    expect(parseSaveSummary({ written: false, error: "cannot encode" }, 2).exitCode).toBe(2);
  });

  it("throws when there is no summary at all, since the outcome is then unknown", () => {
    expect(() => parseSaveSummary(null, 0)).toThrow(/書き込まれたかどうかを判定できません/);
  });

  it("drops reparse entries that are not objects", () => {
    expect(parseSaveSummary({ reparseErrors: ["oops", null, { line: 3 }] }, 0).reparseErrors).toEqual([
      { line: 3, message: "" },
    ]);
  });
});

describe("saveSource", () => {
  it("writes the edited text to a scratch file and hands it to the engine", async () => {
    const log = emptyLog();
    await saveSource(deps({ written: true, path: "C:/assets/a.cbl" }, log), {
      baseDir: "C:/assets",
      path: "cobol/A.cbl",
      editedText: "EDITED\n",
      codepage: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
    });
    expect(log.written).toEqual(["C:/data/edited.tmp:EDITED\n"]);
    expect(log.seen[0].file.replace(/\\/g, "/")).toBe("C:/assets/cobol/A.cbl");
    expect(log.seen[0].editedFile).toBe("C:/data/edited.tmp");
    expect(log.seen[0].db).toBe("C:/data/p.db");
  });

  it("refuses a path outside the base directory", async () => {
    await expect(
      saveSource(deps({ written: true }, emptyLog()), {
        baseDir: "C:/assets",
        path: "../secret.cbl",
        editedText: "x",
      }),
    ).rejects.toThrow(/資産フォルダの外/);
  });

  it("removes the scratch file even when the engine reported nothing", async () => {
    const log = emptyLog();
    await expect(
      saveSource(deps(null, log), { baseDir: "C:/assets", path: "a.cbl", editedText: "x" }),
    ).rejects.toThrow();
    expect(log.removed).toEqual(["C:/data/edited.tmp"]);
  });
});
