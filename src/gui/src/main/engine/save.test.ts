import { describe, expect, it } from "vitest";
import type { SaveRequest, SourceStamp } from "../../shared/ipc";
import { parseSaveSummary, saveSource, type SaveDeps, type SaveFileSystem } from "./save";

const STAMP: SourceStamp = { mtimeMs: 1000, byteSize: 40 };

function deps(
  summary: Record<string, unknown> | null,
  log: { seen: SaveRequest[]; written: string[]; removed: string[] },
  current: SourceStamp | null = STAMP,
): SaveDeps {
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
    stat: async () => current,
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
  it("reads the written flag and the reparse errors", () => {
    const result = parseSaveSummary(
      {
        written: true,
        path: "C:/assets/a.cbl",
        reparseErrors: [{ line: 11, message: "unexpected token" }],
        error: "",
        exitCode: 1,
      },
      0,
    );
    expect(result.written).toBe(true);
    expect(result.reparseErrors).toEqual([{ line: 11, message: "unexpected token" }]);
  });

  it("prefers the summary's exit code over the process's", () => {
    const result = parseSaveSummary(
      {
        written: true,
        path: "C:/assets/a.cbl",
        reparseErrors: [{ line: 11, message: "unexpected token" }],
        error: "",
        exitCode: 1,
      },
      0,
    );
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
  it("hands the edited text to the engine through a scratch file", async () => {
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

  it("answers stale when the original moved off the expected stamp", async () => {
    const log = emptyLog();
    const result = await saveSource(deps({ written: true }, log, { mtimeMs: 2000, byteSize: 40 }), {
      baseDir: "C:/assets",
      path: "a.cbl",
      editedText: "x",
      expectedStamp: STAMP,
    });
    expect(result.stale).toBe(true);
    expect(result.written).toBe(false);
  });

  it("writes nothing when the original moved off the expected stamp", async () => {
    const log = emptyLog();
    const result = await saveSource(deps({ written: true }, log, { mtimeMs: 2000, byteSize: 40 }), {
      baseDir: "C:/assets",
      path: "a.cbl",
      editedText: "x",
      expectedStamp: STAMP,
    });
    expect(log.seen).toEqual([]);
    expect(log.written).toEqual([]);
  });

  it("writes when the original still carries the expected stamp", async () => {
    const log = emptyLog();
    const result = await saveSource(deps({ written: true }, log), {
      baseDir: "C:/assets",
      path: "a.cbl",
      editedText: "x",
      expectedStamp: STAMP,
    });
    expect(result.stale).toBeUndefined();
    expect(log.seen).toHaveLength(1);
  });

  it("writes with no expected stamp, whatever the original's stamp now is", async () => {
    const log = emptyLog();
    await saveSource(deps({ written: true }, log, { mtimeMs: 2000, byteSize: 40 }), {
      baseDir: "C:/assets",
      path: "a.cbl",
      editedText: "x",
    });
    expect(log.seen).toHaveLength(1);
  });

  it("leaves the write to the engine when the original's stamp cannot be read", async () => {
    const log = emptyLog();
    await saveSource(deps({ written: true }, log, null), {
      baseDir: "C:/assets",
      path: "a.cbl",
      editedText: "x",
      expectedStamp: STAMP,
    });
    expect(log.seen).toHaveLength(1);
  });

  it("removes the scratch file even when the engine reported nothing", async () => {
    const log = emptyLog();
    await expect(
      saveSource(deps(null, log), { baseDir: "C:/assets", path: "a.cbl", editedText: "x" }),
    ).rejects.toThrow();
    expect(log.removed).toEqual(["C:/data/edited.tmp"]);
  });
});
