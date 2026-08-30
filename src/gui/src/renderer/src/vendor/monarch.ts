/**
 * Monarch grammars for the mainframe languages, plus the editor theme.
 *
 * All three are line-oriented formats whose meaning depends on the column a character sits in, so
 * every rule lives in the one `root` state and the column-sensitive rules match only at the start of
 * a line (Monaco derives `matchOnlyAtLineStart` from a leading `^`). Monarch carries its state
 * across lines, so pushing a state per area would leave the next line starting mid-area.
 *
 * The COBOL grammar is carried over from V1 unchanged. Its identification area (columns 73-80) is
 * deliberately absent: a Monarch rule matches forward from the current position and cannot be made
 * conditional on a byte column, so the areas are drawn as decorations instead (model/columns).
 */

/** The action of a rule with no capture groups: a token name, or a token plus a state change. */
export interface MonarchTokenAction {
  token: string;
  next?: string;
}

/** An action that picks the token by looking the word up in a list. Keys are "@list" or the word. */
export interface MonarchCasesAction {
  cases: Record<string, string>;
}

/** A rule's action, when it applies to the whole match. */
export type MonarchSimpleAction = string | MonarchTokenAction | MonarchCasesAction;

/** A rule's action. An array is one action per capture group. */
export type MonarchAction = MonarchSimpleAction | MonarchSimpleAction[];

/** One lexical rule: a pattern and its action. */
export type MonarchRule = [RegExp, MonarchAction];

/** State name to rule list. */
export interface MonarchTokenizer {
  [state: string]: MonarchRule[];
}

/** A Monarch language definition, in the shape `setMonarchTokensProvider` takes. */
export interface MonarchLanguage {
  ignoreCase: boolean;
  defaultToken: string;
  keywords?: string[];
  divisions?: string[];
  figurative?: string[];
  operations?: string[];
  macros?: string[];
  tokenizer: MonarchTokenizer;
}

/** One token colouring. */
export interface MonacoThemeRule {
  token: string;
  foreground?: string;
  fontStyle?: string;
}

/** A theme, in the shape `defineTheme` takes. */
export interface MonacoThemeData {
  base: "vs-dark";
  inherit: boolean;
  rules: MonacoThemeRule[];
  colors: Record<string, string>;
}

/** The language ids this module defines. */
export const LANGUAGE_ID = {
  cobol: "cobol-fixed",
  jcl: "jcl",
  bms: "bms",
  json: "json",
} as const;

export const COBOL_INSIGHT_THEME = "cobol-insight";

/* ------------------------------------------------------------------ COBOL fixed format */

/** Words that head a division or a section. */
const DIVISIONS: string[] = [
  "IDENTIFICATION", "ENVIRONMENT", "DATA", "PROCEDURE", "DIVISION", "SECTION", "CONFIGURATION",
  "INPUT-OUTPUT", "WORKING-STORAGE", "LOCAL-STORAGE", "LINKAGE", "FILE-CONTROL", "I-O-CONTROL",
  "SOURCE-COMPUTER", "OBJECT-COMPUTER", "SPECIAL-NAMES", "REPOSITORY", "PROGRAM-ID", "AUTHOR",
  "INSTALLATION", "DATE-WRITTEN", "DATE-COMPILED", "SECURITY", "REMARKS", "DECLARATIVES",
];

/** Figurative constants. None of these spellings is also a keyword, so the lookup stays unambiguous. */
const FIGURATIVE: string[] = [
  "ZERO", "ZEROS", "ZEROES", "SPACE", "SPACES", "HIGH-VALUE", "HIGH-VALUES", "LOW-VALUE",
  "LOW-VALUES", "QUOTE", "QUOTES", "NULL", "NULLS", "ALL", "TRUE", "FALSE",
];

/** Reserved words: verbs, phrases and scope terminators. */
const KEYWORDS: string[] = [
  // Procedure division verbs.
  "ACCEPT", "ADD", "ALTER", "CALL", "CANCEL", "CLOSE", "COMPUTE", "CONTINUE", "DELETE", "DISPLAY",
  "DIVIDE", "ENTRY", "EVALUATE", "EXIT", "GENERATE", "GO", "GOBACK", "IF", "INITIALIZE", "INITIATE",
  "INSPECT", "INVOKE", "MERGE", "MOVE", "MULTIPLY", "OPEN", "PERFORM", "READ", "RELEASE", "RETURN",
  "REWRITE", "SEARCH", "SET", "SORT", "START", "STOP", "STRING", "SUBTRACT", "TERMINATE", "UNSTRING",
  "WRITE", "EXEC", "SQL", "CICS", "XML", "JSON",
  // Scope terminators.
  "END-ADD", "END-CALL", "END-COMPUTE", "END-DELETE", "END-DIVIDE", "END-EVALUATE", "END-EXEC",
  "END-IF", "END-INVOKE", "END-MULTIPLY", "END-PERFORM", "END-READ", "END-RETURN", "END-REWRITE",
  "END-SEARCH", "END-START", "END-STRING", "END-SUBTRACT", "END-UNSTRING", "END-WRITE", "END-XML",
  "END-JSON",
  // Control and conditions.
  "ELSE", "THEN", "WHEN", "OTHER", "THRU", "THROUGH", "UNTIL", "VARYING", "TIMES", "AFTER", "BEFORE",
  "AND", "OR", "NOT", "EQUAL", "GREATER", "LESS", "THAN", "ALSO", "ANY", "NEXT", "SENTENCE",
  // Move and arithmetic phrases.
  "BY", "FROM", "TO", "GIVING", "INTO", "USING", "RETURNING", "OF", "IN", "IS", "ARE", "WITH",
  "CORRESPONDING", "CORR", "ROUNDED", "REMAINDER", "DEPENDING", "ON", "AT", "END", "EOP",
  "INVALID", "KEY", "OVERFLOW", "SIZE", "ERROR", "EXCEPTION", "ADVANCING", "PAGE",
  "DELIMITED", "DELIMITER", "COUNT", "TALLYING", "CHARACTERS", "FIRST", "CONVERTING",
  // Data division clauses.
  "FD", "SD", "PIC", "PICTURE", "VALUE", "VALUES", "REDEFINES", "OCCURS", "INDEXED", "ASCENDING",
  "DESCENDING", "USAGE", "COMP", "COMP-1", "COMP-2", "COMP-3", "COMP-4", "COMP-5", "BINARY",
  "PACKED-DECIMAL", "DISPLAY-1", "NATIONAL", "POINTER", "FUNCTION-POINTER", "PROCEDURE-POINTER",
  "SIGN", "SEPARATE", "JUSTIFIED", "SYNCHRONIZED", "SYNC", "BLANK", "EXTERNAL", "GLOBAL", "RENAMES",
  "FILLER", "CHARACTER",
  // File control clauses.
  "SELECT", "ASSIGN", "ORGANIZATION", "SEQUENTIAL", "RELATIVE", "LINE", "ACCESS", "MODE", "RANDOM",
  "DYNAMIC", "RECORD", "RECORDING", "LABEL", "RECORDS", "STANDARD", "OMITTED", "BLOCK", "CONTAINS",
  "STATUS", "RESERVE", "AREA", "AREAS", "ALTERNATE", "PASSWORD", "FILE", "INPUT", "OUTPUT", "I-O",
  "EXTEND",
  // Text manipulation and the rest.
  "COPY", "REPLACING", "LEADING", "TRAILING", "SUPPRESS", "PROGRAM", "COMMON", "INITIAL",
  "RECURSIVE", "DECIMAL-POINT", "COMMA", "CURRENCY",
];

/**
 * The non-ASCII range a word may be built from. User-defined names may hold full-width characters,
 * so everything from U+00C0 up is a word character except the ideographic space (U+3000). The set is
 * built from code points so that no hard-to-tell-apart character is written into the source.
 */
const WIDE = `${String.fromCharCode(0x00c0)}-${String.fromCharCode(0x2fff)}${String.fromCharCode(0x3001)}-${String.fromCharCode(0xffef)}`;

/** A word: a reserved word, a figurative constant or a user-defined name. */
const WORD = new RegExp(`[A-Za-z${WIDE}][A-Za-z0-9${WIDE}-]*`);

/** The fixed-format COBOL grammar. A fresh definition is built on every call. */
export function cobolLanguage(): MonarchLanguage {
  return {
    ignoreCase: true,
    defaultToken: "identifier",
    keywords: [...KEYWORDS],
    divisions: [...DIVISIONS],
    figurative: [...FIGURATIVE],
    tokenizer: {
      root: [
        // Sequence area plus indicator. Matched only at the start of a line, one rule per indicator.
        [/^(.{6})([*/])(.*)$/, ["sequence", "comment", "comment"]],
        [/^(.{6})(D)/, ["sequence", "debug"]],
        [/^(.{6})(-)/, ["sequence", "continuation"]],
        [/^(.{6})( )/, ["sequence", "white"]],
        [/^(.{6})(.)/, ["sequence", "indicator"]],
        // A line too short to fill the sequence area.
        [/^.{1,6}$/, "sequence"],

        // The body (columns 8-72).
        [/\s+/, "white"],
        // String literals. A doubled quote stands for one quote inside the literal.
        [/'([^']|'')*'/, "string"],
        [/"([^"]|"")*"/, "string"],
        // Hexadecimal, national and DBCS literals.
        [/[XGNZ]'[^']*'/, "string"],
        [/[XGNZ]"[^"]*"/, "string"],
        // A literal that does not close on this line. Fixed format can only continue one through a
        // continuation line, so show it as incomplete.
        [/'([^']|'')*$/, "string.invalid"],
        [/"([^"]|"")*$/, "string.invalid"],
        // Numeric literals.
        [/[+-]?\d+\.\d+/, "number.float"],
        [/[+-]?\d+/, "number"],
        // Words. Figurative constants are decided before reserved words.
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
        [/[*/+=<>&|-]+/, "operator"],
        // Anything else, one character at a time, so tokenising never stalls.
        [/./, "identifier"],
      ],
    },
  };
}

/* ------------------------------------------------------------------ JCL */

/** The operations that can head a JCL statement. */
const JCL_OPERATIONS: string[] = [
  "JOB", "EXEC", "DD", "PROC", "PEND", "SET", "IF", "ELSE", "ENDIF", "INCLUDE", "JCLLIB", "OUTPUT",
  "CNTL", "ENDCNTL", "XMIT", "COMMAND", "NOTIFY",
];

/**
 * The JCL grammar. Statements begin with `//` in columns 1-2, a comment with `//*`, and a delimiter
 * with `/*`. A statement whose name field is empty is a continuation of the one before it.
 */
export function jclLanguage(): MonarchLanguage {
  return {
    ignoreCase: true,
    defaultToken: "identifier",
    operations: [...JCL_OPERATIONS],
    tokenizer: {
      root: [
        // A comment statement, and the delimiter statement that ends in-stream data.
        [/^\/\/\*.*$/, "comment"],
        [/^\/\*.*$/, "keyword.division"],
        // A statement: the // marker, the name field, then the operation.
        [
          /^(\/\/)([^\s]+)(\s+)([A-Za-z]+)/,
          [
            "keyword",
            "identifier.name",
            "white",
            { cases: { "@operations": "keyword.division", "@default": "identifier" } },
          ],
        ],
        // A continuation: the // marker with an empty name field.
        [/^(\/\/)(\s+)/, ["keyword", "white"]],
        [/^\/\//, "keyword"],
        // Everything past the operation: keyword parameters, literals and the trailing comment.
        [/'([^']|'')*'/, "string"],
        [/[A-Za-z#@$][A-Za-z0-9#@$]*(?==)/, "attribute.name"],
        [/\d+/, "number"],
        [/[A-Za-z#@$][A-Za-z0-9#@$.\-*&]*/, "identifier"],
        [/[=,()]/, "delimiter"],
        [/\s+/, "white"],
        [/./, "identifier"],
      ],
    },
  };
}

/* ------------------------------------------------------------------ BMS */

/** The BMS assembler macros. */
const BMS_MACROS: string[] = ["DFHMSD", "DFHMDI", "DFHMDF", "DFHMDX", "END"];

/**
 * The BMS grammar. A macro statement carries its label in column 1, the macro name after the blanks,
 * and keyword parameters after that; an asterisk in column 1 makes the whole line a comment.
 */
export function bmsLanguage(): MonarchLanguage {
  return {
    ignoreCase: true,
    defaultToken: "identifier",
    macros: [...BMS_MACROS],
    tokenizer: {
      root: [
        [/^\*.*$/, "comment"],
        [/^([A-Za-z#@$][A-Za-z0-9#@$]*)(\s+)/, ["identifier.name", "white"]],
        [/'([^']|'')*'/, "string"],
        // A keyword parameter's name, decided by the equals sign that follows it.
        [/[A-Za-z#@$][A-Za-z0-9#@$]*(?==)/, "attribute.name"],
        [
          /[A-Za-z#@$][A-Za-z0-9#@$]*/,
          { cases: { "@macros": "keyword.division", "@default": "identifier" } },
        ],
        [/\d+/, "number"],
        [/[=,()]/, "delimiter"],
        [/\s+/, "white"],
        [/./, "identifier"],
      ],
    },
  };
}

/* ------------------------------------------------------------------ JSON */

/**
 * The JSON grammar, for the rule configuration file.
 *
 * Monaco ships JSON only as a language service (vs/language/json), which brings a worker of its own
 * and with it validation and completion. This editor registers grammars and nothing else — one base
 * worker serves the whole renderer — so JSON is highlighted from a Monarch definition like the other
 * three languages rather than from that service.
 */
export function jsonLanguage(): MonarchLanguage {
  return {
    ignoreCase: false,
    defaultToken: "identifier",
    tokenizer: {
      root: [
        // A member name, told apart from a value by the colon that follows it.
        [/"(?:[^"\\]|\\.)*"(?=\s*:)/, "attribute.name"],
        [/"(?:[^"\\]|\\.)*"/, "string"],
        // A string the line ends in the middle of: JSON has no multi-line string.
        [/"(?:[^"\\]|\\.)*$/, "string.invalid"],
        [/-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/, "number"],
        [/\b(?:true|false|null)\b/, "constant"],
        [/[{}[\],:]/, "delimiter"],
        [/\s+/, "white"],
        [/./, "identifier"],
      ],
    },
  };
}

/* ------------------------------------------------------------------ theme */

/**
 * The editor theme. Monaco does not read CSS custom properties in a theme definition, so the values
 * from styles/tokens.css are repeated here as literals; they are the same palette.
 */
export function cobolInsightTheme(): MonacoThemeData {
  return {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "sequence", foreground: "6b6b6b" },
      { token: "indicator", foreground: "d7ba7d", fontStyle: "bold" },
      { token: "continuation", foreground: "d7ba7d" },
      { token: "debug", foreground: "c586c0" },
      { token: "comment", foreground: "6a9955" },
      { token: "string", foreground: "ce9178" },
      { token: "string.invalid", foreground: "f14c4c", fontStyle: "bold" },
      { token: "number", foreground: "b5cea8" },
      { token: "keyword", foreground: "569cd6", fontStyle: "bold" },
      { token: "keyword.division", foreground: "c586c0", fontStyle: "bold" },
      { token: "constant", foreground: "4fc1ff", fontStyle: "bold" },
      { token: "identifier", foreground: "cccccc" },
      { token: "identifier.name", foreground: "dcdcaa" },
      { token: "attribute.name", foreground: "9cdcfe" },
      { token: "delimiter", foreground: "8b8b8b" },
      { token: "operator", foreground: "d4d4d4" },
    ],
    colors: {
      "editor.background": "#1f1f1f",
      "editor.foreground": "#cccccc",
      "editorLineNumber.foreground": "#6b6b6b",
      "editorLineNumber.activeForeground": "#4daafc",
      "editorRuler.foreground": "#3c3c3c",
      "editorGutter.background": "#1f1f1f",
    },
  };
}

/** The language a path is opened in. Anything not recognised is shown as fixed-format COBOL. */
export function languageIdFor(path: string): string {
  const extension = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
  if (extension === "jcl" || extension === "job" || extension === "prc") {
    return LANGUAGE_ID.jcl;
  }
  if (extension === "bms" || extension === "map") {
    return LANGUAGE_ID.bms;
  }
  if (extension === "json") {
    return LANGUAGE_ID.json;
  }
  return LANGUAGE_ID.cobol;
}
