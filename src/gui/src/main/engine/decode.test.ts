import { describe, expect, it, vi } from "vitest";
import type { DecodeRequest, EngineResult } from "../../shared/ipc";
import { decodeSource, parseDecodeJson, type DecodeDeps, type DecodeFileSystem } from "./decode";

const RESULT_JSON = JSON.stringify({
  text: "000100 IDENTIFICATION DIVISION.\n",
  codepage: "IBM930",
  detected: true,
  soSiPresent: true,
  lines: [{ byteLength: 80, boundaries: [6, 7, 11, 72] }],
  stamp: { mtimeMs: 1700000000000, byteSize: 82 },
  error: "",
});

/** A fake decode runner: it records the request and drops the canned JSON at the requested path. */
function deps(overrides: {
  json?: string | null;
  result?: Partial<EngineResult>;
  seen?: DecodeRequest[];
  removed?: string[];
}): DecodeDeps {
  const files = new Map<string, string>();
  const fs: DecodeFileSystem = {
    realPath: async (absPath) => absPath,
    readText: async (absPath) => {
      const text = files.get(absPath);
      if (text === undefined) throw new Error("no such file");
      return text;
    },
    remove: async (absPath) => {
      overrides.removed?.push(absPath);
    },
  };
  return {
    fs,
    tempFile: () => "C:/data/decode.tmp",
    run: async (request) => {
      overrides.seen?.push(request);
      if (overrides.json !== null && overrides.json !== undefined) {
        files.set(request.outFile, overrides.json);
      }
      return {
        subcommand: "decode",
        exitCode: 0,
        summary: null,
        stdout: "",
        stderr: "",
        outputs: {},
        ...overrides.result,
      };
    },
  };
}

describe("parseDecodeJson", () => {
  it("reads the text, codepage, geometry and stamp", () => {
    const result = parseDecodeJson(RESULT_JSON);
    expect(result.codepage).toBe("IBM930");
    expect(result.detected).toBe(true);
    expect(result.soSiPresent).toBe(true);
    expect(result.lines).toEqual([{ byteLength: 80, boundaries: [6, 7, 11, 72] }]);
    expect(result.stamp).toEqual({ mtimeMs: 1700000000000, byteSize: 82 });
    expect(result.error).toBe("");
  });

  it("fills in defaults for fields an older engine omits", () => {
    const result = parseDecodeJson("{}");
    expect(result.text).toBe("");
    expect(result.lines).toEqual([]);
    expect(result.stamp).toEqual({ mtimeMs: 0, byteSize: 0 });
  });

  it("pads a short boundary array to four entries", () => {
    expect(parseDecodeJson('{"lines":[{"byteLength":5,"boundaries":[1,2]}]}').lines[0]).toEqual({
      byteLength: 5,
      boundaries: [1, 2, 0, 0],
    });
  });
});

describe("decodeSource", () => {
  it("passes the resolved file and the scratch destination to the engine", async () => {
    const seen: DecodeRequest[] = [];
    const result = await decodeSource(deps({ json: RESULT_JSON, seen }), {
      baseDir: "C:/assets",
      path: "cobol/A.cbl",
      codepage: "IBM930",
      db: "C:/data/p.db",
    });
    expect(seen[0].file.replace(/\\/g, "/")).toBe("C:/assets/cobol/A.cbl");
    expect(seen[0].outFile).toBe("C:/data/decode.tmp");
    expect(seen[0].codepage).toBe("IBM930");
    expect(result.text).toContain("IDENTIFICATION DIVISION");
  });

  it("refuses a path outside the base directory", async () => {
    await expect(
      decodeSource(deps({ json: RESULT_JSON }), { baseDir: "C:/assets", path: "../secret.cbl" }),
    ).rejects.toThrow(/outside the asset folder/);
  });

  it("reports the engine's stderr when no result file was written", async () => {
    const result = await decodeSource(
      deps({ json: null, result: { exitCode: 2, stderr: "unsupported codepage\n" } }),
      { baseDir: "C:/assets", path: "cobol/A.cbl" },
    );
    expect(result.error).toBe("unsupported codepage");
    expect(result.text).toBe("");
  });

  it("names the exit code when the engine wrote neither a result nor a message", async () => {
    const result = await decodeSource(deps({ json: null, result: { exitCode: 2 } }), {
      baseDir: "C:/assets",
      path: "cobol/A.cbl",
    });
    expect(result.error).toContain("exit code 2");
  });

  it("removes the scratch file whether or not the decode succeeded", async () => {
    const removed: string[] = [];
    await decodeSource(deps({ json: RESULT_JSON, removed }), {
      baseDir: "C:/assets",
      path: "cobol/A.cbl",
    });
    await decodeSource(deps({ json: null, removed }), { baseDir: "C:/assets", path: "cobol/A.cbl" });
    expect(removed).toEqual(["C:/data/decode.tmp", "C:/data/decode.tmp"]);
  });

  it("uses a fresh scratch file per decode so concurrent reads do not collide", async () => {
    const tempFile = vi.fn(() => `C:/data/decode-${tempFile.mock.calls.length}.tmp`);
    const seen: DecodeRequest[] = [];
    const base = deps({ json: RESULT_JSON, seen });
    await decodeSource({ ...base, tempFile }, { baseDir: "C:/assets", path: "a.cbl" });
    await decodeSource({ ...base, tempFile }, { baseDir: "C:/assets", path: "b.cbl" });
    expect(seen[0].outFile).not.toBe(seen[1].outFile);
  });
});
