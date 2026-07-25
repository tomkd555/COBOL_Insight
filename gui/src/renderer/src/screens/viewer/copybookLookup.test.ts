import { describe, it, expect } from "vitest";
import { copybookCandidates, detectCopyStatements, type CopyStatement } from "./copybookLookup";
import type { SourceCodepage } from "./columns";
import { SAMPLE_INVENTORY } from "../explorer/fixtures";

/** 固定形式の1行を組む。col は本体の開始桁(1 起点)で、7桁目には標識を置く。 */
function fixed(body: string, indicator = " "): string {
  return `      ${indicator}    ${body}`;
}

/** 既定の文字集合(samples は windows-31j)で COPY 文を検出する。 */
function detect(text: string, codepage: SourceCodepage = "Shift_JIS"): CopyStatement[] {
  return detectCopyStatements(text, codepage);
}

describe("detectCopyStatements(COPY 文の検出)", () => {
  it("COPY 文の行番号とコピー句名を取り出す", () => {
    const text = [fixed("DATA DIVISION."), fixed("COPY SYKCPY1.")].join("\n");
    expect(detect(text)).toEqual<CopyStatement[]>([
      { line: 2, name: "SYKCPY1", replacing: null, text: "COPY SYKCPY1." },
    ]);
  });

  it("REPLACING 指定を原文のまま添える", () => {
    const text = fixed("COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.");
    expect(detect(text)).toEqual<CopyStatement[]>([
      {
        line: 1,
        name: "SYKCPY1",
        replacing: "LEADING ==SYK1== BY ==ORD1==",
        text: "COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.",
      },
    ]);
  });

  it("小文字で書いた COPY も検出する", () => {
    expect(detect(fixed("copy sykcpy2.")).map((copy) => copy.name)).toEqual(["sykcpy2"]);
  });

  it("引用符で囲んだコピー句名を取り出す", () => {
    expect(detect(fixed("COPY 'SYKCPY3.CPY'.")).map((copy) => copy.name)).toEqual(["SYKCPY3.CPY"]);
    expect(detect(fixed('COPY "SYKCPY3".')).map((copy) => copy.name)).toEqual(["SYKCPY3"]);
  });

  it("OF・IN による書庫の指定があっても名前だけを取る", () => {
    expect(detect(fixed("COPY SYKCPY1 OF SYKLIB.")).map((copy) => copy.name)).toEqual(["SYKCPY1"]);
  });

  it("注記行(標識欄が * または /)の COPY は検出しない", () => {
    const text = [fixed("COPY SYKCPY1.", "*"), fixed("COPY SYKCPY2.", "/")].join("\n");
    expect(detect(text)).toEqual([]);
  });

  it("一連番号欄・識別欄に現れる COPY は検出しない", () => {
    const identification = `${fixed("MOVE A TO B.").padEnd(72, " ")}COPY XX.`;
    expect(detect("COPY X IDENTIFICATION DIVISION.")).toEqual([]);
    expect(detect(identification)).toEqual([]);
  });

  it("同じファイルの複数の COPY 文をすべて行番号付きで返す", () => {
    const text = [fixed("COPY SYKCPY1."), fixed("MOVE A TO B."), fixed("COPY SYKCPY2.")].join("\n");
    expect(detect(text).map((copy) => [copy.line, copy.name])).toEqual([
      [1, "SYKCPY1"],
      [3, "SYKCPY2"],
    ]);
  });

  it("COPY を含まないソースでは空を返す", () => {
    expect(detect(fixed("MOVE A TO B."))).toEqual([]);
    expect(detect("")).toEqual([]);
  });

  it("語の一部として現れる COPY-FLAG は COPY 文として扱わない", () => {
    expect(detect(fixed("MOVE 1 TO COPY-FLAG."))).toEqual([]);
  });

  it("文字列リテラル内の COPY は COPY 文として扱わない", () => {
    expect(detect(fixed("DISPLAY 'PLEASE COPY THIS TEXT'."))).toEqual([]);
    expect(detect(fixed('DISPLAY "COPY THIS TEXT".'))).toEqual([]);
  });

  it("リテラル内の COPY を挟んでも、同じ行の実際の COPY 文は検出する", () => {
    expect(detect(fixed("DISPLAY 'COPY ME'. COPY SYKCPY1.")).map((copy) => copy.name)).toEqual([
      "SYKCPY1",
    ]);
  });

  it("リテラル内の REPLACING は REPLACING 指定として扱わない", () => {
    expect(detect(fixed("COPY SYKCPY1. DISPLAY 'REPLACING X'.")).map((copy) => copy.replacing)).toEqual([
      null,
    ]);
  });

  it("日本語を含む行では、本体の切り出しをバイト単位で行う", () => {
    // 標識欄まで 7 バイト + 全角 30 文字(60 バイト)= 67 バイト。本体の残りは 5 バイト分である。
    const body = `${"注".repeat(30)} COPY SYKCPY1.`;
    expect(detect(`      ${" "}${body}`, "Shift_JIS")).toEqual([]);
  });

  it("日本語を含んでも本体(72バイト以内)にある COPY 文は検出する", () => {
    const body = `${"注".repeat(10)} COPY SYKCPY1.`;
    expect(detect(`      ${" "}${body}`, "Shift_JIS").map((copy) => copy.name)).toEqual(["SYKCPY1"]);
  });
});

describe("copybookCandidates(コピー句の探索)", () => {
  const INPUT_DIR = "C:\\資産\\SYK";
  const SEARCH_PATHS = ["C:\\資産\\SYK\\copybook", "D:\\共通コピー句"];

  it("資産一覧に登録済みのコピー句を最初の候補にする", () => {
    const candidates = copybookCandidates("SYKCPY1", {
      inputDir: INPUT_DIR,
      copybookPaths: SEARCH_PATHS,
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates[0]).toEqual({ inputDir: INPUT_DIR, path: "copybook/SYKCPY1.cpy", origin: "inventory" });
  });

  it("コピー句名の大文字小文字を無視して資産一覧と突き合わせる", () => {
    const candidates = copybookCandidates("sykcpy1", {
      inputDir: INPUT_DIR,
      copybookPaths: [],
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates.map((candidate) => candidate.path)).toEqual(["copybook/SYKCPY1.cpy"]);
  });

  it("検索パスごとに拡張子なし・.cpy・.CPY の順で候補を並べる", () => {
    const candidates = copybookCandidates("SYKCPY9", {
      inputDir: INPUT_DIR,
      copybookPaths: SEARCH_PATHS,
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates).toEqual([
      { inputDir: "C:\\資産\\SYK\\copybook", path: "SYKCPY9", origin: "searchPath" },
      { inputDir: "C:\\資産\\SYK\\copybook", path: "SYKCPY9.cpy", origin: "searchPath" },
      { inputDir: "C:\\資産\\SYK\\copybook", path: "SYKCPY9.CPY", origin: "searchPath" },
      { inputDir: "D:\\共通コピー句", path: "SYKCPY9", origin: "searchPath" },
      { inputDir: "D:\\共通コピー句", path: "SYKCPY9.cpy", origin: "searchPath" },
      { inputDir: "D:\\共通コピー句", path: "SYKCPY9.CPY", origin: "searchPath" },
    ]);
  });

  it("資産一覧の候補を検索パスの候補より前に置く", () => {
    const candidates = copybookCandidates("SYKCPY1", {
      inputDir: INPUT_DIR,
      copybookPaths: SEARCH_PATHS,
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates.map((candidate) => candidate.origin)).toEqual([
      "inventory",
      "searchPath",
      "searchPath",
      "searchPath",
      "searchPath",
      "searchPath",
      "searchPath",
    ]);
  });

  it("資産フォルダが未確定なら資産一覧の候補を出さない", () => {
    const candidates = copybookCandidates("SYKCPY1", {
      inputDir: null,
      copybookPaths: ["D:\\共通コピー句"],
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates.every((candidate) => candidate.origin === "searchPath")).toBe(true);
  });

  it("探索先がひとつも無ければ空を返す", () => {
    expect(copybookCandidates("SYKCPY9", { inputDir: null, copybookPaths: [], inventory: [] })).toEqual([]);
  });

  it("拡張子付きで書かれた名前も資産一覧のファイル名と突き合わせる", () => {
    const candidates = copybookCandidates("SYKCPY1.CPY", {
      inputDir: INPUT_DIR,
      copybookPaths: [],
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates.map((candidate) => candidate.path)).toEqual(["copybook/SYKCPY1.cpy"]);
  });

  it("コピー句以外の資産は候補にしない", () => {
    const candidates = copybookCandidates("SYK001", {
      inputDir: INPUT_DIR,
      copybookPaths: [],
      inventory: SAMPLE_INVENTORY,
    });
    expect(candidates).toEqual([]);
  });

  it("同じ探索先が重なっても候補を重複させない", () => {
    const candidates = copybookCandidates("SYKCPY9", {
      inputDir: INPUT_DIR,
      copybookPaths: ["D:\\共通コピー句", "D:\\共通コピー句"],
      inventory: [],
    });
    expect(candidates).toHaveLength(3);
  });
});
