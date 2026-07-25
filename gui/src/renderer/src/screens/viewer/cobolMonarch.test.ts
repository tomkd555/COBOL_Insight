import { describe, it, expect } from "vitest";
import {
  COBOL_INSIGHT_THEME,
  COBOL_LANGUAGE_ID,
  cobolInsightTheme,
  cobolMonarchLanguage,
  type CobolMonarchLanguage,
  type MonarchAction,
} from "./cobolMonarch";

/**
 * 字句化の期待をテーブル駆動で検査するための、Monarch 規則の最小の実行器。
 * monaco の monarchLexer と同じ規則で動かす:
 *  - 規則の正規表現は現在位置以降(line.slice(pos))へ先頭固定で照合する。
 *  - 正規表現が ^ で始まる規則は行頭でのみ照合する(monarchCompile の matchOnlyAtLineStart)。
 *  - 動作が配列の規則は捕獲群ごとに動作を割り当てる(グループ照合)。
 *  - ignoreCase は照合と語リストの引きの双方へ及ぶ。
 *  - 照合した規則が無い文字は defaultToken を与えて1文字進む。
 * これにより「どの綴りがどのトークンになるか」を monaco を起動せず検査できる。
 */
interface EmittedToken {
  readonly text: string;
  readonly token: string;
}

function wordLists(definition: CobolMonarchLanguage): Record<string, readonly string[]> {
  return {
    keywords: definition.keywords,
    divisions: definition.divisions,
    figurative: definition.figurative,
  };
}

function resolveAction(definition: CobolMonarchLanguage, action: MonarchAction, text: string): string {
  if (typeof action === "string") {
    return action;
  }
  if (Array.isArray(action)) {
    throw new Error("グループ動作は呼び出し側で展開する");
  }
  if ("cases" in action) {
    const lists = wordLists(definition);
    const needle = definition.ignoreCase ? text.toUpperCase() : text;
    for (const [key, token] of Object.entries(action.cases)) {
      if (key === "@default") {
        continue;
      }
      if (key.startsWith("@")) {
        const list = lists[key.slice(1)];
        if (list === undefined) {
          throw new Error(`未知の語リスト: ${key}`);
        }
        const values = definition.ignoreCase ? list.map((word) => word.toUpperCase()) : list;
        if (values.includes(needle)) {
          return token;
        }
      } else if (key === text) {
        return token;
      }
    }
    return action.cases["@default"] ?? definition.defaultToken;
  }
  return action.token;
}

function tokenize(definition: CobolMonarchLanguage, line: string): EmittedToken[] {
  const tokens: EmittedToken[] = [];
  const flags = definition.ignoreCase ? "i" : "";
  let pos = 0;
  while (pos < line.length) {
    let matchedLength = 0;
    for (const [pattern, action] of definition.tokenizer.root) {
      const source = pattern.source;
      const lineStartOnly = source.startsWith("^");
      if (lineStartOnly && pos > 0) {
        continue;
      }
      const regex = new RegExp(`^(?:${lineStartOnly ? source.slice(1) : source})`, flags);
      const match = regex.exec(line.slice(pos));
      if (match === null || match[0] === "") {
        continue;
      }
      if (Array.isArray(action)) {
        // グループ照合では、捕獲群の合計長が照合全体と一致していなければ monarch は例外を投げる。
        const total = action.reduce((sum, _entry, index) => sum + (match[index + 1] ?? "").length, 0);
        expect(total).toBe(match[0].length);
        action.forEach((groupAction, groupIndex) => {
          const text = match[groupIndex + 1] ?? "";
          if (text !== "") {
            tokens.push({ text, token: resolveAction(definition, groupAction, text) });
          }
        });
      } else {
        tokens.push({ text: match[0], token: resolveAction(definition, action, match[0]) });
      }
      matchedLength = match[0].length;
      break;
    }
    if (matchedLength === 0) {
      tokens.push({ text: line.charAt(pos), token: definition.defaultToken });
      matchedLength = 1;
    }
    pos += matchedLength;
  }
  return tokens;
}

const LANGUAGE = cobolMonarchLanguage();

/** 空白を除いたトークン列(綴りとトークン名の対)。 */
function significant(line: string): [string, string][] {
  return tokenize(LANGUAGE, line)
    .filter((token) => token.token !== "white")
    .map((token) => [token.text, token.token]);
}

/** 指定した綴りに与えられたトークン名。 */
function tokenOf(line: string, text: string): string | undefined {
  return tokenize(LANGUAGE, line).find((token) => token.text === text)?.token;
}

describe("COBOL Monarch 文法の固定形式の欄", () => {
  it("一連番号欄(1〜6桁)を本体と別に扱う", () => {
    expect(significant("000100 IDENTIFICATION DIVISION.")).toEqual([
      ["000100", "sequence"],
      ["IDENTIFICATION", "keyword.division"],
      ["DIVISION", "keyword.division"],
      [".", "delimiter"],
    ]);
  });

  it("一連番号欄に置かれた語は予約語として扱わない", () => {
    expect(tokenOf("MOVE   MOVE A TO B.", "MOVE  ")).toBe("sequence");
  });

  it("6桁に満たない行は全体を一連番号欄として扱う", () => {
    expect(significant("   ")).toEqual([["   ", "sequence"]]);
  });

  it("標識欄が * の行は行末まで注記とする", () => {
    expect(significant("      *  PROGRAM-ID : SYK001  MOVE TO")).toEqual([
      ["      ", "sequence"],
      ["*", "comment"],
      ["  PROGRAM-ID : SYK001  MOVE TO", "comment"],
    ]);
  });

  it("標識欄が / の行も注記とする", () => {
    expect(tokenOf("      /  改ページ", "/")).toBe("comment");
  });

  it("標識欄の D はデバッグ行、- は継続行として区別する", () => {
    expect(tokenOf("      D    DISPLAY 'X'.", "D")).toBe("debug");
    expect(tokenOf("      -    'CONTINUED'", "-")).toBe("continuation");
  });

  it("空白以外の未知の標識は標識欄として示す", () => {
    expect(tokenOf("      X    MOVE A TO B.", "X")).toBe("indicator");
  });

  it("標識欄が空白の行は本体を字句化する", () => {
    expect(significant("           MOVE A TO B.")).toEqual([
      ["      ", "sequence"],
      ["MOVE", "keyword"],
      ["A", "identifier"],
      ["TO", "keyword"],
      ["B", "identifier"],
      [".", "delimiter"],
    ]);
  });
});

describe("COBOL Monarch 文法の本体の字句", () => {
  it.each([
    ["予約語", "           PERFORM VARYING", "PERFORM", "keyword"],
    ["区分・節の見出し", "       DATA DIVISION.", "DATA", "keyword.division"],
    ["図形定数", "           MOVE ZERO TO WS-X.", "ZERO", "constant"],
    ["利用者定義名", "           MOVE ZERO TO WS-X.", "WS-X", "identifier"],
    ["文字列リテラル", "           MOVE 'ABC' TO WS-X.", "'ABC'", "string"],
    ["二重引用符のリテラル", '           MOVE "ABC" TO WS-X.', '"ABC"', "string"],
    ["16進リテラル", "           MOVE X'40' TO WS-X.", "X'40'", "string"],
    ["整数", "       01  WS-COUNT PIC S9(4).", "01", "number"],
    ["小数", "           COMPUTE X = 1.5", "1.5", "number.float"],
    ["括弧", "       01  WS-COUNT PIC S9(4).", "(", "delimiter"],
    ["演算子", "           COMPUTE X = A + B", "+", "operator"],
    ["文の終端子", "           END-IF", "END-IF", "keyword"],
  ])("%s を字句化する", (_label: string, line: string, text: string, expected: string) => {
    expect(tokenOf(line, text)).toBe(expected);
  });

  it("行内で閉じないリテラルは不完全として示す", () => {
    expect(tokenOf("           MOVE 'ABC TO WS-X", "'ABC TO WS-X")).toBe("string.invalid");
  });

  it("小文字で書いた予約語も予約語として扱う(ignoreCase)", () => {
    expect(significant("           move a to b.")).toEqual([
      ["      ", "sequence"],
      ["move", "keyword"],
      ["a", "identifier"],
      ["to", "keyword"],
      ["b", "identifier"],
      [".", "delimiter"],
    ]);
  });

  it("全角文字を含む利用者定義名を1語として扱う", () => {
    expect(tokenOf("           MOVE ERR-受注番号 TO WS-X.", "ERR-受注番号")).toBe("identifier");
  });

  it("PIC と USAGE の指定を予約語として扱う", () => {
    expect(tokenOf("       01  WS-N PIC S9(4) COMP-3.", "PIC")).toBe("keyword");
    expect(tokenOf("       01  WS-N PIC S9(4) COMP-3.", "COMP-3")).toBe("keyword");
  });

  it("EXEC SQL … END-EXEC の綴りを予約語として扱う", () => {
    expect(tokenOf("           EXEC SQL SELECT 1 END-EXEC.", "EXEC")).toBe("keyword");
    expect(tokenOf("           EXEC SQL SELECT 1 END-EXEC.", "SQL")).toBe("keyword");
    expect(tokenOf("           EXEC SQL SELECT 1 END-EXEC.", "END-EXEC")).toBe("keyword");
  });

  it("COPY 文の綴りを予約語として扱い、コピー句名は利用者定義名とする", () => {
    const line = "           COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.";
    expect(tokenOf(line, "COPY")).toBe("keyword");
    expect(tokenOf(line, "REPLACING")).toBe("keyword");
    expect(tokenOf(line, "SYKCPY1")).toBe("identifier");
  });

  it("どの行でも例外なく字句化でき、綴りを取りこぼさない", () => {
    const lines = [
      "",
      " ",
      "      ",
      "       ",
      "      *",
      "           EXEC SQL FETCH C1 INTO :WS-REC END-EXEC.",
      "           IF WS-A NOT = WS-B AND WS-C > 0",
      "           MOVE ALL '*' TO WS-LINE.",
      "      D    DISPLAY '★' WS-受注番号.",
      "           COPY SYKCPY1 REPLACING LEADING ==SYK1== BY ==ORD1==.",
    ];
    for (const line of lines) {
      const tokens = tokenize(LANGUAGE, line);
      expect(tokens.map((token) => token.text).join("")).toBe(line);
    }
  });
});

describe("COBOL Monarch 文法の登録情報", () => {
  it("言語 ID と規則の入口を持つ", () => {
    expect(COBOL_LANGUAGE_ID).toBe("cobol-fixed");
    expect(LANGUAGE.tokenizer.root.length).toBeGreaterThan(0);
    expect(LANGUAGE.ignoreCase).toBe(true);
  });

  it("予約語・区分見出し・図形定数の語リストは重複を持たない", () => {
    for (const list of Object.values(wordLists(LANGUAGE))) {
      expect(new Set(list).size).toBe(list.length);
    }
  });

  it("図形定数と予約語は同じ綴りを持たない(cases の判定が一意になる)", () => {
    const keywords = new Set(LANGUAGE.keywords);
    for (const word of LANGUAGE.figurative) {
      expect(keywords.has(word)).toBe(false);
    }
  });

  it("テーマは字句化で使うトークンをすべて着色する", () => {
    const theme = cobolInsightTheme();
    const themed = new Set(theme.rules.map((rule) => rule.token));
    for (const token of [
      "sequence",
      "indicator",
      "continuation",
      "debug",
      "comment",
      "string",
      "string.invalid",
      "number",
      "keyword",
      "keyword.division",
      "constant",
      "identifier",
      "delimiter",
      "operator",
    ]) {
      expect(themed.has(token)).toBe(true);
    }
    expect(COBOL_INSIGHT_THEME).toBe("cobol-insight");
    expect(theme.base).toBe("vs");
  });
});
