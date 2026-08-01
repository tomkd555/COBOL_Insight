import { describe, it, expect } from "vitest";
import { buildEngineArgs, collectRequestedOutputs } from "./args";
import type { EngineInvocation } from "../../shared/engine-api";

describe("buildEngineArgs", () => {
  it("scan は位置引数 INPUT_DIR と --db、COPY 展開の出力先を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "scan",
      request: { inputDir: "C:/assets", db: "C:/ws/project.db" },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "scan",
      "C:/assets",
      "--db",
      "C:/ws/project.db",
      "--copy-expansion",
      "cobol-insight-copy-expansion.json",
    ]);
  });

  it("COPY 展開の出力先を指定したらその絶対パスを渡す", () => {
    const inv: EngineInvocation = {
      subcommand: "scan",
      request: {
        inputDir: "C:/assets",
        db: "C:/data/cobol-insight.db",
        copyExpansion: "C:/data/cobol-insight-copy-expansion.json",
      },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "scan",
      "C:/assets",
      "--db",
      "C:/data/cobol-insight.db",
      "--copy-expansion",
      "C:/data/cobol-insight-copy-expansion.json",
    ]);
    // 引数へ渡した位置と、renderer が読みにいく位置を一致させる。
    expect(collectRequestedOutputs(inv).copyExpansion).toBe(
      "C:/data/cobol-insight-copy-expansion.json",
    );
  });

  it("copybookPaths と codepageOverrides を繰り返しオプションへ展開する", () => {
    const inv: EngineInvocation = {
      subcommand: "scan",
      request: {
        inputDir: "assets",
        copybookPaths: ["assets/copy", "assets/copybook"],
        codepageOverrides: { "cobol/A.cbl": "IBM-930", "B.cbl": "UTF-8" },
      },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "scan",
      "assets",
      "--copybook-path",
      "assets/copy",
      "--copybook-path",
      "assets/copybook",
      "--codepage",
      "cobol/A.cbl=IBM-930",
      "--codepage",
      "B.cbl=UTF-8",
      "--copy-expansion",
      "cobol-insight-copy-expansion.json",
    ]);
  });

  it("callgraph は各出力先オプションを付与する", () => {
    const inv: EngineInvocation = {
      subcommand: "callgraph",
      request: {
        inputDir: "assets",
        db: "p.db",
        jsonFile: "out/cg.json",
        svgFile: "out/cg.svg",
      },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "callgraph",
      "assets",
      "--db",
      "p.db",
      "--json",
      "out/cg.json",
      "--svg",
      "out/cg.svg",
    ]);
  });

  it("lint は --db を持たず、--sarif と --disable-rule を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "lint",
      request: {
        inputDir: "assets",
        sarifFile: "out/lint.sarif",
        disabledRules: ["R008", "R029"],
      },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "lint",
      "assets",
      "--sarif",
      "out/lint.sarif",
      "--disable-rule",
      "R008",
      "--disable-rule",
      "R029",
    ]);
  });

  it("sql-advise は lint と同じ形で --sarif を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "sql-advise",
      request: { inputDir: "assets", sarifFile: "out/sql.sarif" },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "sql-advise",
      "assets",
      "--sarif",
      "out/sql.sarif",
    ]);
  });

  it("report は --db・--html・--text を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "report",
      request: {
        inputDir: "assets",
        db: "p.db",
        htmlFile: "out/r.html",
        textFile: "out/r.txt",
      },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "report",
      "assets",
      "--db",
      "p.db",
      "--html",
      "out/r.html",
      "--text",
      "out/r.txt",
    ]);
  });

  it("transpile は --language と --out を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "transpile",
      request: { inputDir: "assets", db: "p.db", language: "python", outDir: "out/tp" },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "transpile",
      "assets",
      "--db",
      "p.db",
      "--language",
      "python",
      "--out",
      "out/tp",
    ]);
  });

  it("fix-preview は fix preview の2トークンと --html を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "fix-preview",
      request: { inputDir: "assets", htmlFile: "out/fix.html" },
    };
    expect(buildEngineArgs(inv)).toEqual([
      "fix",
      "preview",
      "assets",
      "--html",
      "out/fix.html",
    ]);
  });

  it("fix-apply は fix apply の2トークンと --out を組み立てる", () => {
    const inv: EngineInvocation = {
      subcommand: "fix-apply",
      request: { inputDir: "assets", outDir: "out/fix" },
    };
    expect(buildEngineArgs(inv)).toEqual(["fix", "apply", "assets", "--out", "out/fix"]);
  });

  it("省略可能オプション未指定なら位置引数のみを出す", () => {
    const inv: EngineInvocation = {
      subcommand: "lint",
      request: { inputDir: "assets" },
    };
    expect(buildEngineArgs(inv)).toEqual(["lint", "assets"]);
  });
});

describe("collectRequestedOutputs", () => {
  it("指定した出力先だけを解決済みパスとして拾う", () => {
    const inv: EngineInvocation = {
      subcommand: "callgraph",
      request: { inputDir: "assets", db: "p.db", jsonFile: "out/cg.json", svgFile: "out/cg.svg" },
    };
    expect(collectRequestedOutputs(inv)).toEqual({
      db: "p.db",
      json: "out/cg.json",
      svg: "out/cg.svg",
    });
  });

  it("scan は常に COPY 展開の対応表を出力先として持つ", () => {
    const inv: EngineInvocation = { subcommand: "scan", request: { inputDir: "assets" } };
    expect(collectRequestedOutputs(inv)).toEqual({
      copyExpansion: "cobol-insight-copy-expansion.json",
    });
  });

  it("fix-apply の出力先ディレクトリを outDir として拾う", () => {
    const inv: EngineInvocation = {
      subcommand: "fix-apply",
      request: { inputDir: "assets", outDir: "out/fix" },
    };
    expect(collectRequestedOutputs(inv)).toEqual({ outDir: "out/fix" });
  });
});
