/**
 * COPY 文の検出(React 非依存の純ロジック)。
 *
 * 検出は固定形式の欄割りに従い、注記行(標識欄が * または /)を除いた本体(8〜72桁)だけを見る。
 * 一連番号欄と識別欄に現れた COPY は原始プログラムの一部ではないため検出しない。
 * 展開そのもの(REPLACING の適用・コピー句の探索・入れ子の解決)は engine の解析が担い、GUI は
 * scan が書いた展開の対応表を原本の位置へ差し込むだけである。ここで検出した COPY 文は、
 * 対応表に展開が無い COPY 文(入れ子の COPY)を見落とさないために用いる。
 */

import { charIndexAfterBytes, type SourceCodepage } from "./columns";

/** 本体(8〜72桁)の直前の桁と終端の桁(バイトで数える)。 */
const BODY_START_COLUMN = 7;
const BODY_LAST_COLUMN = 72;

/** 標識欄(7桁目)の直前の桁。 */
const INDICATOR_COLUMN = 6;

/** COPY 文1件。 */
export interface CopyStatement {
  /** COPY を含む行(1 起点)。 */
  readonly line: number;
  /** コピー句名(引用符で囲まれていた場合は中身)。 */
  readonly name: string;
  /** REPLACING 指定の原文(末尾の句点を除く)。指定が無ければ null。 */
  readonly replacing: string | null;
  /** 検出元の行本文(前後の空白を除いたもの)。 */
  readonly text: string;
}

/** 利用者定義名を構成する文字(COPY-FLAG のような語を1語として切り出すため - を含む)。 */
const WORD_CHARS = /[A-Za-z0-9$#@_-]/;

/** 本体を切り分けた字句。文字列リテラルは中身を1つの字句として保つ。 */
interface BodyToken {
  readonly kind: "word" | "literal";
  /** 語はそのまま、リテラルは引用符を除いた中身。 */
  readonly value: string;
  /** 本体内での字句の終端(0 起点の添字。この位置は字句に含まない)。 */
  readonly end: number;
}

/**
 * 固定形式の本体(8〜72桁)を取り出す。注記行は空文字を返す。桁はバイトで数えるため、
 * DBCS を含む行では文字数と桁が一致しない(columns.ts)。
 */
function bodyOf(line: string, codepage: SourceCodepage): string {
  const indicatorStart = charIndexAfterBytes(line, INDICATOR_COLUMN, codepage);
  const bodyStart = charIndexAfterBytes(line, BODY_START_COLUMN, codepage);
  const indicator = line.slice(indicatorStart, bodyStart);
  if (indicator === "*" || indicator === "/") {
    return "";
  }
  return line.slice(bodyStart, charIndexAfterBytes(line, BODY_LAST_COLUMN, codepage));
}

/**
 * 本体を語とリテラルへ切り分ける。文字列リテラル(' または " で囲んだ範囲)の中身は語として
 * 扱わないため、DISPLAY 'PLEASE COPY THIS TEXT' の COPY は COPY 文にならない。COPY 文の
 * コピー句名は引用符付きでも書けるため、リテラル自体は字句として残す。
 */
function tokenizeBody(body: string): BodyToken[] {
  const tokens: BodyToken[] = [];
  let index = 0;
  while (index < body.length) {
    const char = body.charAt(index);
    if (char === "'" || char === '"') {
      const close = body.indexOf(char, index + 1);
      const end = close < 0 ? body.length : close + 1;
      tokens.push({ kind: "literal", value: body.slice(index + 1, close < 0 ? body.length : close), end });
      index = end;
      continue;
    }
    if (WORD_CHARS.test(char)) {
      let end = index + 1;
      while (end < body.length && WORD_CHARS.test(body.charAt(end))) {
        end += 1;
      }
      tokens.push({ kind: "word", value: body.slice(index, end), end });
      index = end;
      continue;
    }
    index += 1;
  }
  return tokens;
}

/** 字句列から COPY 文1件を組む。COPY が無い行、名前が続かない行は null。 */
function copyStatementOf(body: string, line: number): CopyStatement | null {
  const tokens = tokenizeBody(body);
  const copyIndex = tokens.findIndex(
    (token) => token.kind === "word" && token.value.toUpperCase() === "COPY",
  );
  if (copyIndex < 0) {
    return null;
  }
  const nameToken = tokens[copyIndex + 1];
  if (nameToken === undefined || nameToken.value === "") {
    return null;
  }
  const replacingToken = tokens
    .slice(copyIndex + 1)
    .find((token) => token.kind === "word" && token.value.toUpperCase() === "REPLACING");
  return {
    line,
    name: nameToken.value,
    replacing:
      replacingToken === undefined
        ? null
        : body.slice(replacingToken.end).trim().replace(/\.$/, "").trim(),
    text: body.trim(),
  };
}

/** ソース本文から COPY 文を行番号付きで取り出す。 */
export function detectCopyStatements(text: string, codepage: SourceCodepage): CopyStatement[] {
  const statements: CopyStatement[] = [];
  text.split(/\r\n|\n|\r/).forEach((line, index) => {
    const statement = copyStatementOf(bodyOf(line, codepage), index + 1);
    if (statement !== null) {
      statements.push(statement);
    }
  });
  return statements;
}
