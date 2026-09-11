import {
  COPY_EXPANSION_FILE_NAME,
  type EngineCommonOptions,
  type EngineInvocation,
  type EngineOutputs,
} from "../../shared/ipc";

/**
 * Assembles the engine CLI arguments deterministically from a typed request.
 *
 * Subcommands do not all accept the same options (lint and fix have no --db, report takes no asset
 * folder at all), so the shared part and the per-subcommand part are kept separate. The positional
 * INPUT_DIR comes first where a subcommand has one.
 *
 * Rule configuration is one file passed as `--rules` to every subcommand that consults rules.
 */
export function buildEngineArgs(invocation: EngineInvocation): string[] {
  switch (invocation.subcommand) {
    case "scan": {
      const r = invocation.request;
      // The COPY expansion table is always written: the inline expansion in the source view has no
      // other source. Only when no destination is given does the engine's own default apply.
      return [
        "scan",
        ...common(r),
        ...opt("--db", r.db),
        "--copy-expansion",
        r.copyExpansion ?? COPY_EXPANSION_FILE_NAME,
      ];
    }
    case "lint": {
      const r = invocation.request;
      // --scope narrows what is analysed inside the asset folder; the folder itself stays the
      // positional INPUT_DIR, so the copybook search paths and every relative path are unchanged.
      return [
        "lint",
        ...common(r),
        ...repeated("--scope", r.scope),
        ...opt("--sarif", r.sarifFile),
        ...opt("--sql-sarif", r.sqlSarifFile),
      ];
    }
    case "report": {
      // No asset folder and no parsing options: report only reads artefacts already on disk.
      const r = invocation.request;
      return [
        "report",
        ...opt("--db", r.db),
        ...opt("--sarif", r.sarifFile),
        ...opt("--sql-sarif", r.sqlSarifFile),
        ...opt("--html", r.htmlFile),
        ...opt("--text", r.textFile),
      ];
    }
    case "translate": {
      const r = invocation.request;
      return [
        "translate",
        ...common(r),
        ...opt("--db", r.db),
        ...opt("--language", r.language),
        ...opt("--out", r.outDir),
      ];
    }
    case "fix": {
      const r = invocation.request;
      return ["fix", ...common(r), ...opt("--out", r.outDir)];
    }
    case "rules": {
      // Neither call takes an asset folder, so `common` does not apply. The output is always JSON,
      // which is the shape the screens read.
      const r = invocation.request;
      return ["rules", ...opt("--rules", r.rulesFile)];
    }
    case "save": {
      // The only subcommand that takes the file to write directly rather than an asset folder.
      const r = invocation.request;
      return [
        "save",
        "--file",
        r.file,
        "--edited",
        r.editedFile,
        ...opt("--codepage", r.codepage),
        ...repeated("--copybook-path", r.copybookPaths),
        ...opt("--db", r.db),
      ];
    }
    case "decode": {
      const r = invocation.request;
      return [
        "decode",
        "--file",
        r.file,
        ...opt("--codepage", r.codepage),
        ...opt("--db", r.db),
        "--out",
        r.outFile,
      ];
    }
  }
}

/**
 * Collects the files and directories an invocation writes into {@link EngineOutputs}: what the
 * request named explicitly, plus what a subcommand always writes (scan's COPY expansion table).
 * The renderer reads its artefacts from these paths.
 */
export function collectRequestedOutputs(invocation: EngineInvocation): EngineOutputs {
  // save overwrites an original and decode writes a scratch file; neither produces an artefact the
  // renderer reads through EngineOutputs.
  if (invocation.subcommand === "save" || invocation.subcommand === "decode") {
    return {};
  }
  const r = invocation.request;
  const outputs: EngineOutputs = {};
  if (invocation.subcommand === "scan") {
    // Return exactly the value that went into the arguments. Making one side absolute would put the
    // written location and the read location out of step.
    outputs.copyExpansion =
      "copyExpansion" in r && r.copyExpansion !== undefined
        ? r.copyExpansion
        : COPY_EXPANSION_FILE_NAME;
  }
  if ("db" in r && r.db !== undefined) outputs.db = r.db;
  if ("sarifFile" in r && r.sarifFile !== undefined) outputs.sarif = r.sarifFile;
  if ("sqlSarifFile" in r && r.sqlSarifFile !== undefined) outputs.sqlSarif = r.sqlSarifFile;
  if ("htmlFile" in r && r.htmlFile !== undefined) outputs.html = r.htmlFile;
  if ("textFile" in r && r.textFile !== undefined) outputs.text = r.textFile;
  if ("outDir" in r && r.outDir !== undefined) outputs.outDir = r.outDir;
  return outputs;
}

/** The positional INPUT_DIR plus the copybook paths, codepage overrides and rule file. */
function common(options: EngineCommonOptions): string[] {
  const args: string[] = [options.inputDir];
  for (const path of options.copybookPaths ?? []) {
    args.push("--copybook-path", path);
  }
  for (const [file, charset] of Object.entries(options.codepageOverrides ?? {})) {
    args.push("--codepage", `${file}=${charset}`);
  }
  if (options.rulesFile !== undefined) {
    args.push("--rules", options.rulesFile);
  }
  return args;
}

function opt(name: string, value: string | undefined): string[] {
  return value === undefined ? [] : [name, value];
}

function repeated(name: string, values: string[] | undefined): string[] {
  const args: string[] = [];
  for (const value of values ?? []) {
    args.push(name, value);
  }
  return args;
}
