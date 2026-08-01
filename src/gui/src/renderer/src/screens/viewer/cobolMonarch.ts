/**
 * IBM Enterprise COBOL(固定形式)の Monarch 文法と、ソースビューア用のテーマ。
 *
 * 固定形式の欄割りを扱うため、規則はすべて root ひとつに置き、欄の判定は行頭でのみ照合する規則
 * (monaco が正規表現の先頭の ^ から導く matchOnlyAtLineStart)で行う。Monarch の状態は行を越えて
 * 引き継がれるため、欄ごとに状態を積むと次の行が本体の状態で始まってしまう。1行の中で位置により
 * 規則を切り替える方式にすることで、行単位の欄割りを正しく保つ。
 *
 * 扱う欄は次のとおり。1〜6桁=一連番号欄、7桁目=標識欄(* と / は注記行、D はデバッグ行、
 * - は継続行)、8〜72桁=本体。73〜80桁の識別欄は Monarch では扱えない(規則は現在位置以降の
 * 文字列にしか照合できず、桁位置を条件にできない)。識別欄は桁ルーラと、viewerModel の
 * identificationRanges が返す範囲への装飾で示す。
 */

/** 捕獲群を持たない規則の動作(トークン名、または遷移付きの動作)。 */
export interface MonarchTokenAction {
  token: string;
  next?: string;
}

/** 語リストの引きでトークンを決める動作。キーは "@リスト名" または綴りそのもの。 */
export interface MonarchCasesAction {
  cases: Record<string, string>;
}

/** 規則の動作。配列は捕獲群ごとの動作(グループ照合)である。 */
export type MonarchAction = string | MonarchTokenAction | MonarchCasesAction | string[];

/** 1件の字句規則(正規表現と動作)。 */
export type MonarchRule = [RegExp, MonarchAction];

/** 状態名から規則の並びを引く表。 */
export interface MonarchTokenizer {
  [state: string]: MonarchRule[];
}

/** monaco の setMonarchTokensProvider へ渡す COBOL 文法定義。 */
export interface CobolMonarchLanguage {
  ignoreCase: boolean;
  defaultToken: string;
  /** 予約語(動詞・句・終端子)。 */
  keywords: string[];
  /** 区分・節の見出しに現れる語。 */
  divisions: string[];
  /** 図形定数。 */
  figurative: string[];
  tokenizer: MonarchTokenizer;
}

/** テーマのトークン着色1件。 */
export interface MonacoThemeRule {
  token: string;
  foreground?: string;
  fontStyle?: string;
}

/** monaco の defineTheme へ渡すテーマ定義。 */
export interface MonacoThemeData {
  base: "vs";
  inherit: boolean;
  rules: MonacoThemeRule[];
  colors: Record<string, string>;
}

/** 文法とテーマの登録に用いる monaco の最小の面。実体は vendor/monacoEditor の返す API である。 */
export interface MonacoRegistrationTarget {
  languages: {
    register(language: { id: string }): void;
    setMonarchTokensProvider(languageId: string, definition: CobolMonarchLanguage): void;
  };
  editor: {
    defineTheme(themeName: string, theme: MonacoThemeData): void;
  };
}

export const COBOL_LANGUAGE_ID = "cobol-fixed";
export const COBOL_INSIGHT_THEME = "cobol-insight";

/** 区分・節の見出しに現れる語。 */
const DIVISIONS: string[] = [
  "IDENTIFICATION",
  "ENVIRONMENT",
  "DATA",
  "PROCEDURE",
  "DIVISION",
  "SECTION",
  "CONFIGURATION",
  "INPUT-OUTPUT",
  "WORKING-STORAGE",
  "LOCAL-STORAGE",
  "LINKAGE",
  "FILE-CONTROL",
  "I-O-CONTROL",
  "SOURCE-COMPUTER",
  "OBJECT-COMPUTER",
  "SPECIAL-NAMES",
  "REPOSITORY",
  "PROGRAM-ID",
  "AUTHOR",
  "INSTALLATION",
  "DATE-WRITTEN",
  "DATE-COMPILED",
  "SECURITY",
  "REMARKS",
  "DECLARATIVES",
];

/** 図形定数。予約語と綴りが重ならないようにし、cases の判定を一意に保つ。 */
const FIGURATIVE: string[] = [
  "ZERO",
  "ZEROS",
  "ZEROES",
  "SPACE",
  "SPACES",
  "HIGH-VALUE",
  "HIGH-VALUES",
  "LOW-VALUE",
  "LOW-VALUES",
  "QUOTE",
  "QUOTES",
  "NULL",
  "NULLS",
  "ALL",
  "TRUE",
  "FALSE",
];

/** 予約語(動詞・句・終端子)。 */
const KEYWORDS: string[] = [
  // 手続き部の動詞
  "ACCEPT", "ADD", "ALTER", "CALL", "CANCEL", "CLOSE", "COMPUTE", "CONTINUE", "DELETE", "DISPLAY",
  "DIVIDE", "ENTRY", "EVALUATE", "EXIT", "GENERATE", "GO", "GOBACK", "IF", "INITIALIZE", "INITIATE",
  "INSPECT", "INVOKE", "MERGE", "MOVE", "MULTIPLY", "OPEN", "PERFORM", "READ", "RELEASE", "RETURN",
  "REWRITE", "SEARCH", "SET", "SORT", "START", "STOP", "STRING", "SUBTRACT", "TERMINATE", "UNSTRING",
  "WRITE", "EXEC", "SQL", "CICS", "XML", "JSON",
  // 終端子
  "END-ADD", "END-CALL", "END-COMPUTE", "END-DELETE", "END-DIVIDE", "END-EVALUATE", "END-EXEC",
  "END-IF", "END-INVOKE", "END-MULTIPLY", "END-PERFORM", "END-READ", "END-RETURN", "END-REWRITE",
  "END-SEARCH", "END-START", "END-STRING", "END-SUBTRACT", "END-UNSTRING", "END-WRITE", "END-XML",
  "END-JSON",
  // 制御・条件
  "ELSE", "THEN", "WHEN", "OTHER", "THRU", "THROUGH", "UNTIL", "VARYING", "TIMES", "AFTER", "BEFORE",
  "AND", "OR", "NOT", "EQUAL", "GREATER", "LESS", "THAN", "ALSO", "ANY", "NEXT", "SENTENCE",
  // 転記・演算の句
  "BY", "FROM", "TO", "GIVING", "INTO", "USING", "RETURNING", "OF", "IN", "IS", "ARE", "WITH",
  "CORRESPONDING", "CORR", "ROUNDED", "REMAINDER", "DEPENDING", "ON", "AT", "END", "EOP",
  "INVALID", "KEY", "OVERFLOW", "SIZE", "ERROR", "EXCEPTION", "ADVANCING", "PAGE",
  "DELIMITED", "DELIMITER", "COUNT", "TALLYING", "CHARACTERS", "FIRST", "CONVERTING",
  // データ部の句
  "FD", "SD", "PIC", "PICTURE", "VALUE", "VALUES", "REDEFINES", "OCCURS", "INDEXED", "ASCENDING",
  "DESCENDING", "USAGE", "COMP", "COMP-1", "COMP-2", "COMP-3", "COMP-4", "COMP-5", "BINARY",
  "PACKED-DECIMAL", "DISPLAY-1", "NATIONAL", "POINTER", "FUNCTION-POINTER", "PROCEDURE-POINTER",
  "SIGN", "SEPARATE", "JUSTIFIED", "SYNCHRONIZED", "SYNC", "BLANK", "EXTERNAL", "GLOBAL", "RENAMES",
  "FILLER", "CHARACTER",
  // ファイル管理の句
  "SELECT", "ASSIGN", "ORGANIZATION", "SEQUENTIAL", "RELATIVE", "LINE", "ACCESS", "MODE", "RANDOM",
  "DYNAMIC", "RECORD", "RECORDING", "LABEL", "RECORDS", "STANDARD", "OMITTED", "BLOCK", "CONTAINS",
  "STATUS", "RESERVE", "AREA", "AREAS", "ALTERNATE", "PASSWORD", "FILE", "INPUT", "OUTPUT", "I-O",
  "EXTEND",
  // 原文操作・その他
  "COPY", "REPLACING", "LEADING", "TRAILING", "SUPPRESS", "PROGRAM", "COMMON", "INITIAL",
  "RECURSIVE", "DECIMAL-POINT", "COMMA", "CURRENCY",
];

/**
 * 語(予約語・利用者定義名)の構成文字のうち、ASCII 以外の範囲。全角文字を含む利用者定義名を
 * 1語として扱うため U+00C0 以降を含め、全角空白(U+3000)だけを語から除く。文字集合はコード
 * ポイントから組み、原文へ判別しにくい文字を直接書かない。
 */
const WIDE = `${String.fromCharCode(0x00c0)}-${String.fromCharCode(0x2fff)}${String.fromCharCode(0x3001)}-${String.fromCharCode(0xffef)}`;

/** 語(予約語・図形定数・利用者定義名)の正規表現。 */
const WORD = new RegExp(`[A-Za-z${WIDE}][A-Za-z0-9${WIDE}-]*`);

/** COBOL 固定形式の Monarch 文法定義を返す。呼び出しごとに新しい定義を組む。 */
export function cobolMonarchLanguage(): CobolMonarchLanguage {
  return {
    ignoreCase: true,
    defaultToken: "identifier",
    keywords: [...KEYWORDS],
    divisions: [...DIVISIONS],
    figurative: [...FIGURATIVE],
    tokenizer: {
      root: [
        // 一連番号欄 + 標識欄。行頭でのみ照合し、標識欄の種別ごとに動作を分ける。
        [/^(.{6})([*\/])(.*)$/, ["sequence", "comment", "comment"]],
        [/^(.{6})(D)/, ["sequence", "debug"]],
        [/^(.{6})(-)/, ["sequence", "continuation"]],
        [/^(.{6})( )/, ["sequence", "white"]],
        [/^(.{6})(.)/, ["sequence", "indicator"]],
        // 一連番号欄に満たない短い行。
        [/^.{1,6}$/, "sequence"],

        // 本体(8〜72桁)。
        [/\s+/, "white"],
        // 文字列リテラル。引用符2つはリテラル中の引用符1文字を表す。
        [/'([^']|'')*'/, "string"],
        [/"([^"]|"")*"/, "string"],
        // 16進・国別・DBCS のリテラル。
        [/[XGNZ]'[^']*'/, "string"],
        [/[XGNZ]"[^"]*"/, "string"],
        // 行内で閉じないリテラル。固定形式では継続行でしか跨げないため、不完全として示す。
        [/'([^']|'')*$/, "string.invalid"],
        [/"([^"]|"")*$/, "string.invalid"],
        // 数字リテラル。
        [/[+-]?\d+\.\d+/, "number.float"],
        [/[+-]?\d+/, "number"],
        // 語。図形定数を予約語より先に判定する。
        [
          WORD,
          {
            cases: {
              "@divisions": "keyword.division",
              "@figurative": "constant",
              "@keywords": "keyword",
              "@default": "identifier",
            },
          },
        ],
        [/[.,;:()]/, "delimiter"],
        [/[*\/+=<>&|-]+/, "operator"],
        // 上のどれにも当たらない1文字。字句化が止まらないようにする。
        [/./, "identifier"],
      ],
    },
  };
}

/**
 * ソースビューアのテーマ。Monarch が出すトークン名(末尾に言語 ID が付く)を着色する。
 * 配色はデザイントークンと同じ値を用いるが、monaco はテーマ定義で CSS 変数を解釈しないため実値で書く。
 */
export function cobolInsightTheme(): MonacoThemeData {
  return {
    base: "vs",
    inherit: true,
    rules: [
      { token: "sequence", foreground: "9aa4b1" },
      { token: "indicator", foreground: "b25b00", fontStyle: "bold" },
      { token: "continuation", foreground: "b25b00" },
      { token: "debug", foreground: "6a4e9c" },
      { token: "comment", foreground: "7c8692" },
      { token: "string", foreground: "a05c1f" },
      { token: "string.invalid", foreground: "c50f1f", fontStyle: "bold" },
      { token: "number", foreground: "2f6d45" },
      { token: "keyword", foreground: "005fb8", fontStyle: "bold" },
      { token: "keyword.division", foreground: "6a4e9c", fontStyle: "bold" },
      { token: "constant", foreground: "2f6d45", fontStyle: "bold" },
      { token: "identifier", foreground: "1f2328" },
      { token: "delimiter", foreground: "5c6670" },
      { token: "operator", foreground: "0b5394" },
    ],
    colors: {
      "editor.background": "#ffffff",
      "editor.foreground": "#1f2328",
      "editorLineNumber.foreground": "#9aa4b1",
      "editorLineNumber.activeForeground": "#005fb8",
      "editorRuler.foreground": "#e4e7eb",
      "editorGutter.background": "#f6f7f9",
    },
  };
}

let registered = false;

/**
 * COBOL 固定形式の言語とテーマを monaco へ登録する。登録は1度だけ行う
 * (monaco は同一 ID の再登録を重複した言語として扱う)。
 */
export function registerCobolLanguage(monaco: MonacoRegistrationTarget): void {
  if (registered) {
    return;
  }
  monaco.languages.register({ id: COBOL_LANGUAGE_ID });
  monaco.languages.setMonarchTokensProvider(COBOL_LANGUAGE_ID, cobolMonarchLanguage());
  monaco.editor.defineTheme(COBOL_INSIGHT_THEME, cobolInsightTheme());
  registered = true;
}
