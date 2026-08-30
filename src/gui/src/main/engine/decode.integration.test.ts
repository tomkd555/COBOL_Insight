import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { copyFile, mkdtemp, readFile, realpath, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { beforeAll, describe, expect, it } from "vitest";
import type { DecodeResult } from "../../shared/ipc";
import { boundariesOf, editorCodepageOf } from "../../renderer/src/model/columns";
import { resolveEngineLaunch } from "./launch";
import { runEngine, type EngineProcess, type EngineSpawn } from "./run";
import { decodeSource, type DecodeDeps, type DecodeFileSystem } from "./decode";
import { saveSource, type SaveDeps, type SaveFileSystem } from "./save";

/**
 * The EBCDIC read and write path, against the real engine.
 *
 * This is the only test that can prove the claim the whole design rests on: that CP930 and CP939 are
 * decoded correctly, that the byte-column boundaries the renderer draws its areas from are right on
 * a line holding double-byte characters, and that writing one line back leaves every other line's
 * bytes exactly as they were.
 *
 * It self-skips when the development artefact or a JDK is missing, so a checkout that has not run
 * `gradlew :engine:app:installDist` still has a green test suite.
 */

/** app.getAppPath() equivalent: this file sits at src/gui/src/main/engine. */
const APP_ROOT = resolve(__dirname, "..", "..", "..");
const REPO_ROOT = resolve(APP_ROOT, "..", "..");
const FIXTURES = join(REPO_ROOT, "samples", "encoding");
const INSTALL_DIR = join(APP_ROOT, "..", "engine", "app", "build", "install", "app");
const JAVA_HOME = process.env["JAVA_HOME"];

const ready =
  existsSync(join(INSTALL_DIR, "lib")) &&
  existsSync(FIXTURES) &&
  JAVA_HOME !== undefined &&
  JAVA_HOME !== "" &&
  existsSync(join(JAVA_HOME, "bin"));

const engineSpawn: EngineSpawn = (command, args, options) =>
  spawn(command, args, { env: options.env, cwd: options.cwd }) as unknown as EngineProcess;

const launch = (): ReturnType<typeof resolveEngineLaunch> =>
  resolveEngineLaunch({
    isPackaged: false,
    platform: process.platform,
    resourcesPath: "",
    appRoot: APP_ROOT,
    javaHome: JAVA_HOME,
  });

const scratch = (prefix: string): string => join(tmpdir(), `ci-${prefix}-${randomUUID()}.tmp`);

const decodeFs: DecodeFileSystem = {
  realPath: (absPath) => realpath(absPath),
  readText: (absPath) => readFile(absPath, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

const decodeDeps: DecodeDeps = {
  fs: decodeFs,
  tempFile: () => scratch("decode"),
  run: (request) =>
    runEngine({ spawn: engineSpawn, env: process.env }, launch(), { subcommand: "decode", request }),
};

const saveFs: SaveFileSystem = {
  realPath: (absPath) => realpath(absPath),
  writeText: (absPath, text) => writeFile(absPath, text, "utf-8"),
  remove: (absPath) => rm(absPath, { force: true }),
};

/** The four fixtures, each with the codepage it is written in. */
const FILES: readonly { name: string; codepage: string }[] = [
  { name: "SYKENC1_UTF8.cbl", codepage: "UTF-8" },
  { name: "SYKENC1_SJIS.cbl", codepage: "Shift_JIS" },
  { name: "SYKENC1_CP930.cbl", codepage: "IBM930" },
  { name: "SYKENC1_CP939.cbl", codepage: "IBM939" },
];

/** Splits a file's bytes into its lines, so untouched lines can be compared byte for byte. */
async function byteLines(path: string): Promise<string[]> {
  const bytes = await readFile(path);
  return bytes
    .toString("latin1")
    .split("\n")
    .map((line) => Buffer.from(line, "latin1").toString("hex"));
}

describe.skipIf(!ready)("decoding and saving the EBCDIC fixtures, for real", () => {
  const decoded = new Map<string, DecodeResult>();

  beforeAll(async () => {
    for (const file of FILES) {
      decoded.set(
        file.name,
        await decodeSource(decodeDeps, {
          baseDir: FIXTURES,
          path: file.name,
          codepage: file.codepage,
        }),
      );
    }
  }, 240_000);

  it("decodes all four codepages to the same text", () => {
    for (const file of FILES) {
      const result = decoded.get(file.name);
      expect(result?.error, `${file.name}: ${result?.error ?? ""}`).toBe("");
    }
    const utf8 = decoded.get("SYKENC1_UTF8.cbl")?.text;
    expect(utf8).toContain("PROGRAM-ID.  SYKENC1.");
    // The point of the whole decode path: EBCDIC reaches the renderer as the same text as UTF-8.
    expect(decoded.get("SYKENC1_CP930.cbl")?.text).toBe(utf8);
    expect(decoded.get("SYKENC1_CP939.cbl")?.text).toBe(utf8);
    expect(decoded.get("SYKENC1_SJIS.cbl")?.text).toBe(utf8);
  });

  it("reports the shift bytes only for the EBCDIC pair", () => {
    expect(decoded.get("SYKENC1_CP930.cbl")?.soSiPresent).toBe(true);
    expect(decoded.get("SYKENC1_SJIS.cbl")?.soSiPresent).toBe(false);
  });

  it("puts the area boundaries on the right characters of a double-byte line", () => {
    /*
     * Line 3 of the Shift_JIS fixture, byte for byte:
     *   columns 1-6   six spaces           (the sequence area)
     *   column 7      '*'                  (the indicator: a comment line)
     *   columns 8-9   two spaces
     *   columns 10-11 the first double-byte character
     *   columns 12-13 the second
     * So byte column 7 starts at character 6, column 8 at character 7, and column 12 falls exactly
     * on the second double-byte character, at character 10.
     */
    const sjis = decoded.get("SYKENC1_SJIS.cbl");
    const line = sjis?.lines[2];
    expect(sjis?.text.split("\n")[2].startsWith("      *  ")).toBe(true);
    expect(line?.boundaries[0]).toBe(6);
    expect(line?.boundaries[1]).toBe(7);
    expect(line?.boundaries[2]).toBe(10);

    // The same source line in CP930 carries a shift-out byte before the run, so byte column 12
    // falls inside the first double-byte character instead. An area never starts half way through a
    // character, so the boundary moves on to the second one and lands on the same character as it
    // does in Shift_JIS, for a different reason.
    expect(decoded.get("SYKENC1_CP930.cbl")?.lines[2].boundaries[2]).toBe(10);
  });

  it("agrees with the boundaries the editor recomputes while the text is edited", () => {
    for (const file of FILES) {
      const result = decoded.get(file.name);
      const codepage = editorCodepageOf(result?.codepage);
      const lines = (result?.text ?? "").split("\n");
      lines.forEach((line, index) => {
        const reported = result?.lines[index];
        if (reported === undefined) {
          return;
        }
        expect(reported.boundaries, `${file.name} line ${index + 1}`).toEqual(
          boundariesOf(line, codepage),
        );
      });
    }
  });

  it("writes one line back and leaves every other line's bytes untouched", async () => {
    const dir = await mkdtemp(join(tmpdir(), "ci-save-"));
    try {
      const name = "SYKENC1_SJIS.cbl";
      const target = join(dir, name);
      await copyFile(join(FIXTURES, name), target);
      const before = await byteLines(target);

      const first = await decodeSource(decodeDeps, {
        baseDir: dir,
        path: name,
        codepage: "Shift_JIS",
      });
      expect(first.error).toBe("");
      const lines = first.text.split("\n");
      const index = lines.findIndex((line) => line.includes("PROGRAM-ID : SYKENC1"));
      expect(index).toBeGreaterThan(0);
      // The same number of characters, so only the bytes of this one line can move.
      lines[index] = lines[index].replace("SYKENC1", "SYKENC2");

      const saved = await saveSource(
        {
          fs: saveFs,
          tempFile: () => scratch("edited"),
          dbPath: join(dir, "unused.db"),
          run: (request) =>
            runEngine({ spawn: engineSpawn, env: process.env }, launch(), {
              subcommand: "save",
              request,
            }),
        } satisfies SaveDeps,
        { baseDir: dir, path: name, editedText: lines.join("\n"), codepage: "Shift_JIS" },
      );
      expect(saved.error).toBe("");
      expect(saved.written).toBe(true);

      const after = await byteLines(target);
      expect(after).toHaveLength(before.length);
      after.forEach((hex, position) => {
        if (position === index) {
          expect(hex, "the edited line").not.toBe(before[position]);
        } else {
          expect(hex, `line ${position + 1}`).toBe(before[position]);
        }
      });

      const again = await decodeSource(decodeDeps, {
        baseDir: dir,
        path: name,
        codepage: "Shift_JIS",
      });
      expect(again.text).toBe(lines.join("\n"));
    } finally {
      await rm(dir, { recursive: true, force: true });
    }
  }, 240_000);
});
