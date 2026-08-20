import {
  COPY_EXPANSION_FILE_NAME,
  type EngineCommonOptions,
  type EngineInvocation,
  type EngineOutputs,
} from "../../shared/engine-api";

/**
 * engine CLI サブコマンドの引数を、型付きリクエストから決定論的に組み立てる純関数。
 * サブコマンドごとに受理するオプションが異なる(lint/sql-lint/fix は --db を持たない)ため、
 * 共通部と個別部を分けて扱う。picocli の位置引数 INPUT_DIR を先頭に置く。
 */
export function buildEngineArgs(invocation: EngineInvocation): string[] {
  switch (invocation.subcommand) {
    case "scan": {
      const r = invocation.request;
      // COPY 展開の対応表は常に書かせる。ソースビューアのインライン展開がこれを唯一の供給源とする。
      // 出力先の指定が無い場合だけ、engine が既定の SQLite を置く場所(作業ディレクトリ)へ委ねる。
      return [
        "scan",
        ...common(r),
        ...opt("--db", r.db),
        "--copy-expansion",
        r.copyExpansion ?? COPY_EXPANSION_FILE_NAME,
      ];
    }
    case "call-graph": {
      const r = invocation.request;
      return [
        "call-graph",
        ...common(r),
        ...opt("--db", r.db),
        ...opt("--json", r.jsonFile),
        ...opt("--dot", r.dotFile),
        ...opt("--svg", r.svgFile),
        ...opt("--png", r.pngFile),
      ];
    }
    case "lint": {
      const r = invocation.request;
      return [
        "lint",
        ...common(r),
        ...opt("--sarif", r.sarifFile),
        ...opt("--rule-config", r.ruleConfigFile),
        ...opt("--user-rules", r.userRulesFile),
      ];
    }
    case "sql-lint": {
      const r = invocation.request;
      return [
        "sql-lint",
        ...common(r),
        ...opt("--sarif", r.sarifFile),
        ...opt("--rule-config", r.ruleConfigFile),
      ];
    }
    case "report": {
      const r = invocation.request;
      return [
        "report",
        ...common(r),
        ...opt("--db", r.db),
        ...opt("--html", r.htmlFile),
        ...opt("--text", r.textFile),
        ...opt("--rule-config", r.ruleConfigFile),
        ...opt("--user-rules", r.userRulesFile),
      ];
    }
    case "rules": {
      // 資産フォルダを取らないため common を挟まない。出力は常に JSON とし、画面が読む形へ揃える。
      const r = invocation.request;
      return [
        "rules",
        "--json",
        ...opt("--user-rules", r.userRulesFile),
        ...opt("--rule-config", r.ruleConfigFile),
      ];
    }
    case "save": {
      // 資産フォルダの位置引数を取らず、書き戻す原本を --file で直に受ける唯一のサブコマンドである。
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
    case "fix-preview": {
      const r = invocation.request;
      return ["fix", "preview", ...common(r), ...opt("--html", r.htmlFile)];
    }
    case "fix-apply": {
      const r = invocation.request;
      return ["fix", "apply", ...common(r), ...opt("--out", r.outDir)];
    }
  }
}

/**
 * 起動で書かれる出力先ファイル/ディレクトリを {@link EngineOutputs} へ集約する純関数。
 * リクエストで明示指定したものと、サブコマンドが常に書くもの(scan の COPY 展開)を併せる。
 * renderer はここで返るパスを起点に成果物ファイルを読む。
 */
export function collectRequestedOutputs(invocation: EngineInvocation): EngineOutputs {
  // save は原本を書き戻すだけで成果物ファイルを作らない(--db は読むためだけに渡す)。
  if (invocation.subcommand === "save") {
    return {};
  }
  const r = invocation.request;
  const outputs: EngineOutputs = {};
  if (invocation.subcommand === "scan") {
    // 引数へ渡した位置と同じ値を返す。片方だけ絶対パス化すると、書かれた位置と読む位置がずれる。
    outputs.copyExpansion =
      ("copyExpansion" in r && r.copyExpansion !== undefined
        ? r.copyExpansion
        : COPY_EXPANSION_FILE_NAME);
  }
  if ("db" in r && r.db !== undefined) outputs.db = r.db;
  if ("jsonFile" in r && r.jsonFile !== undefined) outputs.json = r.jsonFile;
  if ("dotFile" in r && r.dotFile !== undefined) outputs.dot = r.dotFile;
  if ("svgFile" in r && r.svgFile !== undefined) outputs.svg = r.svgFile;
  if ("pngFile" in r && r.pngFile !== undefined) outputs.png = r.pngFile;
  if ("sarifFile" in r && r.sarifFile !== undefined) outputs.sarif = r.sarifFile;
  if ("htmlFile" in r && r.htmlFile !== undefined) outputs.html = r.htmlFile;
  if ("textFile" in r && r.textFile !== undefined) outputs.text = r.textFile;
  if ("outDir" in r && r.outDir !== undefined) outputs.outDir = r.outDir;
  return outputs;
}

/** 位置引数 INPUT_DIR とコピー句探索パス・コードページ手動指定を組み立てる。 */
function common(options: EngineCommonOptions): string[] {
  const args: string[] = [options.inputDir];
  for (const path of options.copybookPaths ?? []) {
    args.push("--copybook-path", path);
  }
  for (const [file, charset] of Object.entries(options.codepageOverrides ?? {})) {
    args.push("--codepage", `${file}=${charset}`);
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
