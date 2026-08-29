import { describe, expect, it } from "vitest";
import { COPY_EXPANSION_FILE_NAME } from "../../shared/ipc";
import { buildEngineArgs, collectRequestedOutputs } from "./args";

describe("buildEngineArgs", () => {
  it("puts the asset folder first and repeats the copybook paths", () => {
    expect(
      buildEngineArgs({
        subcommand: "lint",
        request: { inputDir: "C:/assets", copybookPaths: ["C:/cpy1", "C:/cpy2"] },
      }),
    ).toEqual(["lint", "C:/assets", "--copybook-path", "C:/cpy1", "--copybook-path", "C:/cpy2"]);
  });

  it("renders each codepage override as FILE=CHARSET", () => {
    expect(
      buildEngineArgs({
        subcommand: "lint",
        request: { inputDir: "C:/assets", codepageOverrides: { "a.cbl": "IBM930" } },
      }),
    ).toEqual(["lint", "C:/assets", "--codepage", "a.cbl=IBM930"]);
  });

  it("passes the one rule file as --rules", () => {
    expect(
      buildEngineArgs({
        subcommand: "lint",
        request: { inputDir: "C:/assets", rulesFile: "C:/data/rules.json", sarifFile: "C:/out.sarif" },
      }),
    ).toEqual(["lint", "C:/assets", "--rules", "C:/data/rules.json", "--sarif", "C:/out.sarif"]);
  });

  it("omits --rules when no rule file is given", () => {
    expect(buildEngineArgs({ subcommand: "sql-lint", request: { inputDir: "C:/assets" } })).toEqual([
      "sql-lint",
      "C:/assets",
    ]);
  });

  it("always makes scan write the COPY expansion table", () => {
    expect(buildEngineArgs({ subcommand: "scan", request: { inputDir: "C:/assets" } })).toEqual([
      "scan",
      "C:/assets",
      "--copy-expansion",
      COPY_EXPANSION_FILE_NAME,
    ]);
    expect(
      buildEngineArgs({
        subcommand: "scan",
        request: { inputDir: "C:/assets", db: "C:/p.db", copyExpansion: "C:/copy.json" },
      }),
    ).toEqual(["scan", "C:/assets", "--db", "C:/p.db", "--copy-expansion", "C:/copy.json"]);
  });

  it("passes every call-graph output that was requested", () => {
    expect(
      buildEngineArgs({
        subcommand: "call-graph",
        request: {
          inputDir: "C:/assets",
          db: "C:/p.db",
          jsonFile: "C:/g.json",
          dotFile: "C:/g.dot",
          svgFile: "C:/g.svg",
          pngFile: "C:/g.png",
        },
      }),
    ).toEqual([
      "call-graph",
      "C:/assets",
      "--db",
      "C:/p.db",
      "--json",
      "C:/g.json",
      "--dot",
      "C:/g.dot",
      "--svg",
      "C:/g.svg",
      "--png",
      "C:/g.png",
    ]);
  });

  it("splits fix into the preview and apply subcommands", () => {
    expect(buildEngineArgs({ subcommand: "fix-preview", request: { inputDir: "C:/a" } })).toEqual([
      "fix",
      "preview",
      "C:/a",
    ]);
    expect(
      buildEngineArgs({ subcommand: "fix-apply", request: { inputDir: "C:/a", outDir: "C:/out" } }),
    ).toEqual(["fix", "apply", "C:/a", "--out", "C:/out"]);
  });

  it("gives rules no asset folder and always asks for JSON", () => {
    expect(buildEngineArgs({ subcommand: "rules", request: {} })).toEqual(["rules", "--json"]);
    expect(buildEngineArgs({ subcommand: "rules", request: { rulesFile: "C:/r.json" } })).toEqual([
      "rules",
      "--json",
      "--rules",
      "C:/r.json",
    ]);
  });

  it("gives save the original and the edited file directly", () => {
    expect(
      buildEngineArgs({
        subcommand: "save",
        request: {
          file: "C:/assets/a.cbl",
          editedFile: "C:/tmp/edited.tmp",
          codepage: "Shift_JIS",
          copybookPaths: ["C:/cpy"],
          db: "C:/p.db",
        },
      }),
    ).toEqual([
      "save",
      "--file",
      "C:/assets/a.cbl",
      "--edited",
      "C:/tmp/edited.tmp",
      "--codepage",
      "Shift_JIS",
      "--copybook-path",
      "C:/cpy",
      "--db",
      "C:/p.db",
    ]);
  });

  it("gives decode the file to read and the result file to write", () => {
    expect(
      buildEngineArgs({
        subcommand: "decode",
        request: { file: "C:/assets/a.cbl", codepage: "IBM930", db: "C:/p.db", outFile: "C:/t.json" },
      }),
    ).toEqual([
      "decode",
      "--file",
      "C:/assets/a.cbl",
      "--codepage",
      "IBM930",
      "--db",
      "C:/p.db",
      "--out",
      "C:/t.json",
    ]);
  });

  it("passes translate its language and output directory", () => {
    expect(
      buildEngineArgs({
        subcommand: "translate",
        request: { inputDir: "C:/a", db: "C:/p.db", language: "both", outDir: "C:/gen" },
      }),
    ).toEqual(["translate", "C:/a", "--db", "C:/p.db", "--language", "both", "--out", "C:/gen"]);
  });
});

describe("collectRequestedOutputs", () => {
  it("reports scan's project file and COPY expansion table", () => {
    expect(
      collectRequestedOutputs({
        subcommand: "scan",
        request: { inputDir: "C:/a", db: "C:/p.db", copyExpansion: "C:/copy.json" },
      }),
    ).toEqual({ db: "C:/p.db", copyExpansion: "C:/copy.json" });
  });

  it("returns the same default the arguments used when scan names no destination", () => {
    expect(collectRequestedOutputs({ subcommand: "scan", request: { inputDir: "C:/a" } })).toEqual({
      copyExpansion: COPY_EXPANSION_FILE_NAME,
    });
  });

  it("reports nothing for save and decode", () => {
    expect(
      collectRequestedOutputs({
        subcommand: "save",
        request: { file: "C:/a.cbl", editedFile: "C:/e.tmp" },
      }),
    ).toEqual({});
    expect(
      collectRequestedOutputs({
        subcommand: "decode",
        request: { file: "C:/a.cbl", outFile: "C:/t.json" },
      }),
    ).toEqual({});
  });

  it("reports the SARIF file lint was asked for", () => {
    expect(
      collectRequestedOutputs({
        subcommand: "lint",
        request: { inputDir: "C:/a", sarifFile: "C:/out.sarif" },
      }),
    ).toEqual({ sarif: "C:/out.sarif" });
  });
});
