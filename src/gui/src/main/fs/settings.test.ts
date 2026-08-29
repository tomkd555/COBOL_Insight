import { describe, expect, it } from "vitest";
import { APP_SETTINGS_VERSION, emptyAppSettings } from "../../shared/settings";
import { readSettings, writeSettings } from "./settings";
import type { JsonFileSystem } from "./jsonFile";

/** An in-memory filesystem so the round trip is exercised without touching disk. */
function memory(initial: Record<string, string> = {}): JsonFileSystem & { files: Map<string, string> } {
  const files = new Map(Object.entries(initial));
  return {
    files,
    readText: async (path) => {
      const text = files.get(path);
      if (text === undefined) throw new Error("no such file");
      return text;
    },
    writeText: async (path, text) => {
      files.set(path, text);
    },
    exists: async (path) => files.has(path),
  };
}

const PATH = "C:/data/settings.json";

describe("readSettings", () => {
  it("returns the empty settings when nothing has been stored", async () => {
    await expect(readSettings(memory(), PATH)).resolves.toEqual(emptyAppSettings());
  });

  it("returns the empty settings rather than failing on an empty or corrupt file", async () => {
    await expect(readSettings(memory({ [PATH]: "" }), PATH)).resolves.toEqual(emptyAppSettings());
    await expect(readSettings(memory({ [PATH]: "{oops" }), PATH)).resolves.toEqual(emptyAppSettings());
  });

  it("reads the settings out of the versioned wrapper", async () => {
    const stored = JSON.stringify({
      version: 2,
      settings: { severityThreshold: "medium", copybookPaths: ["C:/cpy"], lastInputDir: "C:/a" },
    });
    const settings = await readSettings(memory({ [PATH]: stored }), PATH);
    expect(settings.severityThreshold).toBe("medium");
    expect(settings.copybookPaths).toEqual(["C:/cpy"]);
    expect(settings.lastInputDir).toBe("C:/a");
  });

  it("drops fields whose type is not what the shape expects", async () => {
    const stored = JSON.stringify({
      settings: { severityThreshold: 3, copybookPaths: ["ok", 7], paneSizes: { side: 200, bad: "x" } },
    });
    const settings = await readSettings(memory({ [PATH]: stored }), PATH);
    expect(settings.severityThreshold).toBe("");
    expect(settings.copybookPaths).toEqual(["ok"]);
    expect(settings.paneSizes).toEqual({ side: 200 });
  });
});

describe("writeSettings", () => {
  it("round-trips through the file", async () => {
    const fs = memory();
    const settings = {
      severityThreshold: "high",
      defaultEncoding: "Shift_JIS",
      copybookPaths: ["C:/cpy"],
      lastInputDir: "C:/assets",
      paneSizes: { side: 320, panel: 240 },
    };
    await writeSettings(fs, PATH, settings);
    await expect(readSettings(fs, PATH)).resolves.toEqual(settings);
  });

  it("stamps the current version and ends the file with a newline", async () => {
    const fs = memory();
    await writeSettings(fs, PATH, emptyAppSettings());
    const text = fs.files.get(PATH) ?? "";
    expect(JSON.parse(text).version).toBe(APP_SETTINGS_VERSION);
    expect(text.endsWith("\n")).toBe(true);
  });
});
