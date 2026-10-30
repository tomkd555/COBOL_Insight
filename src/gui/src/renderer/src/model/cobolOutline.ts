/**
 * The structure of one fixed-format COBOL source, read from its text (pure, independent of React and
 * of Monaco).
 *
 * The editor needs the divisions, sections and paragraphs, the ranges that fold, and the places a
 * section or paragraph is referred to, and it needs them for the text as it stands while it is being
 * edited. The engine's own structure is as of the last scan, so this module reads the lines itself.
 * It reads what the layout of fixed format shows: a header starts in area A, a statement block runs
 * from its verb to its END- terminator, a period ends every open block.
 *
 * ponytail: one program per file. A nested program's sections and paragraphs are listed in line
 * order with the outer program's and a name is looked up file-wide; scope the lookup by program if
 * nested programs with clashing names ever turn up.
 */

import { RESERVED_WORDS } from "../vendor/monarch";
import { FIXED_OFFSETS, SEQUENCE_COLUMNS } from "./columns";

/**
 * A division header in fixed format: the sequence area, a blank indicator, then a word that starts
 * in area A, followed by DIVISION.
 */
const DIVISION_HEADER = new RegExp(
  `^.{${SEQUENCE_COLUMNS}}[ ]\\s{0,${FIXED_OFFSETS[2] - FIXED_OFFSETS[1] - 1}}\\S+\\s+DIVISION\\b`,
  "i",
);

export type OutlineKind = "division" | "section" | "paragraph" | "declaratives";

export interface OutlineNode {
  /** What identifies the node across edits: its name, with the section's in front for a paragraph. */
  readonly key: string;
  readonly kind: OutlineKind;
  readonly name: string;
  /** The section a paragraph belongs to, or null. */
  readonly section: string | null;
  /** How deep the node sits in the outline, 0 for a division. */
  readonly depth: number;
  /** One-based, both ends included. */
  readonly startLine: number;
  readonly endLine: number;
  /** Whether a PERFORM or GO TO can name it: a section or paragraph of the procedure division. */
  readonly procedure: boolean;
}

/** A range that folds, in one-based lines. The first line stays in view. */
export interface FoldRange {
  readonly start: number;
  readonly end: number;
  readonly comment: boolean;
}

/**
 * One place the text names a section or paragraph, in Monaco's one-based columns. `endColumn` is the
 * column just past the name, and it counts as part of the reference (resolveAt): a press with the
 * caret right after the name still names it.
 */
export interface OutlineRef {
  readonly line: number;
  readonly startColumn: number;
  readonly endColumn: number;
  /** The key of the node it names. */
  readonly key: string;
}

export interface CobolOutline {
  /** In source order. */
  readonly nodes: readonly OutlineNode[];
  readonly folds: readonly FoldRange[];
  readonly refs: readonly OutlineRef[];
  readonly byKey: ReadonlyMap<string, OutlineNode>;
  /** The key of where execution starts, or null when the text holds no procedure. */
  readonly defaultRoot: string | null;
}

/** Character offsets of the indicator, area A, area B and the identification area; -1 when out of reach. */
export type AreaOffsets = readonly [number, number, number, number];

/** The key of a node: its name, with the section's in front for a paragraph of a section. */
export function outlineKey(name: string, section: string | null): string {
  return section === null ? name : `${section}/${name}`;
}

/** Words that end a sentence's first word without naming a paragraph, beyond what the grammar reserves. */
const NEVER_A_NAME = new Set(["EJECT", "SKIP1", "SKIP2", "SKIP3", "TITLE", "RUN", "USE", "ID"]);

const DIVISION_NAMES = new Set(["IDENTIFICATION", "ID", "ENVIRONMENT", "DATA", "PROCEDURE"]);

/** Verbs whose statement an END- terminator can close. */
const BLOCK_VERBS = new Set([
  "IF", "EVALUATE", "READ", "WRITE", "REWRITE", "DELETE", "START", "RETURN", "SEARCH", "CALL",
  "COMPUTE", "ADD", "SUBTRACT", "MULTIPLY", "DIVIDE", "STRING", "UNSTRING", "INVOKE", "XML", "JSON",
  "EXEC",
]);

/** The words that follow PERFORM where its statements are written in line. */
const INLINE_PERFORM = new Set(["UNTIL", "VARYING", "WITH", "TEST"]);
/** The two spellings of the word that names the end of a PERFORM range. */
const THRU_WORDS = new Set(["THRU", "THROUGH"]);

// Same word characters as the grammar (vendor/monarch WORD), with a leading digit allowed: a
// paragraph is commonly named 1000-INIT.
const WIDE = "\\u00c0-\\u2fff\\u3001-\\uffef";
const TOKEN = new RegExp(
  `'(?:[^']|'')*'?|"(?:[^"]|"")*"?|\\*>.*|[A-Za-z0-9${WIDE}][A-Za-z0-9${WIDE}-]*|\\.(?=\\s|$)`,
  "g",
);

// Only a line holding a character outside ASCII needs its offsets worked out.
// eslint-disable-next-line no-control-regex
const ASCII_ONLY = /^[\x00-\x7f]*$/;

interface OpenNode {
  key: string;
  kind: OutlineKind;
  name: string;
  section: string | null;
  depth: number;
  startLine: number;
  endLine: number;
  procedure: boolean;
}

interface Candidate {
  readonly line: number;
  readonly start: number;
  readonly name: string;
  readonly section: string | null;
  qualifier: string | null;
}

interface Block {
  readonly verb: string;
  readonly line: number;
}

/**
 * Reads the outline, the folding ranges and the references. `offsetsOf` gives the area offsets of a
 * line that holds double-byte characters, where the areas are byte columns; without it every line is
 * read at the fixed character offsets.
 */
export function scanCobol(
  lines: readonly string[],
  offsetsOf?: (line: string) => AreaOffsets,
): CobolOutline {
  const nodes: OpenNode[] = [];
  const folds: FoldRange[] = [];
  const candidates: Candidate[] = [];

  // A text with no division header is a member copied into a procedure division or a data
  // division; read as procedure, a data member yields nothing, since a level number names nothing.
  const hasDivision = lines.some((line) => DIVISION_HEADER.test(line));
  let division = hasDivision ? "" : "PROCEDURE";

  let openDivision: OpenNode | null = null;
  let openDeclaratives: OpenNode | null = null;
  let openSection: OpenNode | null = null;
  let openParagraph: OpenNode | null = null;
  let dataEntryStart = 0;
  /** The last line that held code, which is where a node closed by the next header ends. */
  let lastCode = 0;
  let commentRunStart = 0;
  let previousWasComment = false;

  let blocks: Block[] = [];
  let inExec = false;
  let literalOpen = false;
  /** PERFORM waiting for the words after it to say whether its statements are in line. */
  let perform: { line: number; words: string[] } | null = null;
  let lastCandidate: Candidate | null = null;
  let qualifying = false;
  /** Whether the next name is a procedure's: one name, or a list of them after GO TO. */
  let naming: "one" | "list" | null = null;
  /** Whether the words belong to an ALTER statement, whose names are all procedures'. */
  let altering = false;
  /** The word before the one in hand, kept across lines and let go at a period. */
  let previousWord = "";

  let rootKey: string | null = null;
  let rootSettled = false;
  /** A PROCEDURE DIVISION header whose USING phrase runs on: its sentence is still open. */
  let headerOpen = false;

  const close = (node: OpenNode | null): void => {
    if (node === null) {
      return;
    }
    node.endLine = Math.max(node.startLine, lastCode);
    if (node.endLine > node.startLine) {
      folds.push({ start: node.startLine, end: node.endLine, comment: false });
    }
  };
  const closeDataEntry = (): void => {
    if (dataEntryStart > 0 && lastCode > dataEntryStart) {
      folds.push({ start: dataEntryStart, end: lastCode, comment: false });
    }
    dataEntryStart = 0;
  };
  const closeParagraph = (): void => {
    close(openParagraph);
    openParagraph = null;
  };
  const closeSection = (): void => {
    closeParagraph();
    close(openSection);
    openSection = null;
    closeDataEntry();
  };
  const closeDivision = (): void => {
    closeSection();
    close(openDeclaratives);
    openDeclaratives = null;
    close(openDivision);
    openDivision = null;
  };
  const open = (kind: OutlineKind, name: string, line: number, depth: number): OpenNode => {
    const procedure = division === "PROCEDURE" && (kind === "section" || kind === "paragraph");
    const section = kind === "paragraph" && openSection !== null && procedure ? openSection.name : null;
    const node: OpenNode = {
      key: kind === "division" ? `${name} DIVISION` : outlineKey(name, section),
      kind,
      name,
      section,
      depth,
      startLine: line,
      endLine: line,
      procedure,
    };
    nodes.push(node);
    return node;
  };
  const depthBelow = (...parents: (OpenNode | null)[]): number =>
    parents.filter((parent) => parent !== null).length;

  for (let index = 0; index < lines.length; index++) {
    const text = lines[index] ?? "";
    const line = index + 1;
    const [indicatorAt, areaA, areaB, identification] =
      offsetsOf === undefined || ASCII_ONLY.test(text) ? FIXED_OFFSETS : offsetsOf(text);
    const indicator = indicatorAt < 0 ? " " : (text[indicatorAt] ?? " ");
    const contentEnd = identification < 0 ? text.length : identification;
    const blank = areaA < 0 || text.slice(areaA, contentEnd).trim() === "";
    const comment = indicator === "*" || indicator === "/";

    if (comment) {
      if (!previousWasComment) {
        commentRunStart = line;
      }
      previousWasComment = true;
      continue;
    }
    if (previousWasComment && line - 1 > commentRunStart) {
      folds.push({ start: commentRunStart, end: line - 1, comment: true });
    }
    previousWasComment = false;
    if (blank || indicator === "D" || indicator === "d") {
      continue;
    }

    // The words and periods of the line, with the literals and the trailing remark passed over.
    const words: { text: string; at: number }[] = [];
    let from = areaA;
    if (indicator === "-" && literalOpen) {
      // The continued literal reopens at the first quote of the continuation line.
      const reopen = text.slice(areaA, contentEnd).search(/['"]/);
      from = reopen < 0 ? contentEnd : areaA + reopen;
    }
    literalOpen = false;
    const body = contentEnd < text.length ? text.slice(0, contentEnd) : text;
    TOKEN.lastIndex = from;
    for (let match = TOKEN.exec(body); match !== null; match = TOKEN.exec(body)) {
      const token = match[0];
      const first = token[0] ?? "";
      if (first === "'" || first === '"') {
        literalOpen = token.length < 2 || !token.endsWith(first);
      } else if (first === "*") {
        break;
      } else {
        words.push({ text: token.toUpperCase(), at: match.index });
      }
    }
    if (words.length === 0) {
      lastCode = line;
      continue;
    }

    // ---- headers: the first word starts in area A
    let consumed = 0;
    const head = words[0] as { text: string; at: number };
    const second = words[1]?.text ?? "";
    const inAreaA = indicator !== "-" && !inExec && head.at < (areaB < 0 ? text.length : areaB);
    if (headerOpen) {
      const period = words.findIndex((word) => word.text === ".");
      headerOpen = period < 0;
      consumed = period < 0 ? words.length : period + 1;
    } else if (inAreaA) {
      if (DIVISION_NAMES.has(head.text) && second === "DIVISION") {
        closeDivision();
        blocks = [];
        division = head.text === "ID" ? "IDENTIFICATION" : head.text;
        openDivision = open("division", division, line, 0);
        if (division === "PROCEDURE") {
          rootSettled = false;
          rootKey = null;
          headerOpen = !words.some((word) => word.text === ".");
        }
        consumed = words.length;
      } else if (head.text === "END" && second === "PROGRAM") {
        closeDivision();
        blocks = [];
        division = "";
        consumed = words.length;
      } else if (head.text === "DECLARATIVES" && second === ".") {
        closeSection();
        blocks = [];
        openDeclaratives = open("declaratives", "DECLARATIVES", line, depthBelow(openDivision));
        consumed = 2;
      } else if (head.text === "END" && second === "DECLARATIVES") {
        closeSection();
        close(openDeclaratives);
        openDeclaratives = null;
        blocks = [];
        rootSettled = false;
        rootKey = null;
        consumed = words.length;
      } else if (
        second === "SECTION" &&
        head.text !== "." &&
        // A name made of digits alone is a section's only in the procedure division.
        (division === "PROCEDURE" || !/^\d+$/.test(head.text))
      ) {
        closeSection();
        blocks = [];
        openSection = open("section", head.text, line, depthBelow(openDivision, openDeclaratives));
        const period = words.findIndex((word) => word.text === ".");
        consumed = period < 0 ? words.length : period + 1;
      } else if (
        division === "PROCEDURE" &&
        second === "." &&
        !RESERVED_WORDS.has(head.text) &&
        !NEVER_A_NAME.has(head.text)
      ) {
        closeParagraph();
        blocks = [];
        openParagraph = open(
          "paragraph",
          head.text,
          line,
          depthBelow(openDivision, openDeclaratives, openSection),
        );
        consumed = 2;
      } else if (division === "DATA" && /^(?:FD|SD|RD|0?1)$/.test(head.text)) {
        closeDataEntry();
        dataEntryStart = line;
      }
      const opened = nodes[nodes.length - 1];
      if (consumed > 0 && opened !== undefined && opened.startLine === line && opened.procedure) {
        if (!rootSettled && openDeclaratives === null) {
          rootKey = opened.key;
          rootSettled = true;
        }
      }
    }
    if (consumed === 0 && division === "PROCEDURE" && !rootSettled && openDeclaratives === null) {
      // Statements ahead of the first paragraph: execution starts at the division itself.
      rootKey = openDivision?.key ?? null;
      rootSettled = true;
    }

    // ---- statements
    if (division === "PROCEDURE") {
      for (let position = consumed; position < words.length; position++) {
        const word = words[position] as { text: string; at: number };
        if (inExec) {
          if (word.text === "END-EXEC") {
            inExec = false;
            closeBlock("EXEC", line, position === 0);
          }
          continue;
        }
        if (word.text === ".") {
          blocks = [];
          perform = null;
          lastCandidate = null;
          qualifying = false;
          naming = null;
          altering = false;
          previousWord = "";
          continue;
        }
        if (perform !== null) {
          perform.words.push(word.text);
          const [after, then] = perform.words;
          if (INLINE_PERFORM.has(after ?? "") || then === "TIMES") {
            blocks.push({ verb: "PERFORM", line: perform.line });
            perform = null;
          } else if (perform.words.length >= 2) {
            perform = null;
          }
        }
        if (word.text === "PERFORM") {
          perform = { line, words: [] };
        } else if (word.text === "EXEC") {
          blocks.push({ verb: "EXEC", line });
          inExec = true;
        } else if (BLOCK_VERBS.has(word.text)) {
          blocks.push({ verb: word.text, line });
        } else if (word.text.startsWith("END-")) {
          closeBlock(word.text.slice(4), line, position === 0);
        }

        // A word is a reference only where a statement names a procedure: after PERFORM, THRU,
        // GO TO, ALTER, PROCEED TO and INPUT or OUTPUT PROCEDURE, and as the section qualifying one.
        // A data item that shares a paragraph's name is thereby left as the data item it is.
        // The word before, on this line or the one above: a statement may break between GO and TO.
        const previous = previousWord;
        previousWord = word.text;
        if ((word.text === "OF" || word.text === "IN") && lastCandidate !== null) {
          qualifying = true;
        } else if (
          !RESERVED_WORDS.has(word.text) &&
          // Digits alone name a procedure only where a statement names one (PERFORM 0100 THRU 0200).
          (!/^\d+$/.test(word.text) || naming !== null || qualifying)
        ) {
          if (naming === null && !qualifying) {
            lastCandidate = null;
            continue;
          }
          const candidate: Candidate = {
            line,
            start: word.at,
            name: word.text,
            section: openSection?.name ?? null,
            qualifier: null,
          };
          if (qualifying && lastCandidate !== null) {
            lastCandidate.qualifier = word.text;
          }
          candidates.push(candidate);
          lastCandidate = candidate;
          qualifying = false;
          // GO TO takes a list of names before DEPENDING ON; the others take one.
          naming = naming === "list" ? "list" : null;
        } else {
          const named = lastCandidate;
          lastCandidate = null;
          qualifying = false;
          // PERFORM n TIMES: the name before TIMES is the count's data item.
          if (word.text === "TIMES" && named !== null && candidates[candidates.length - 1] === named) {
            candidates.pop();
          }
          if (word.text === "ALTER") {
            // ALTER p1 TO [PROCEED TO] p2, any number of times: every name in it is a procedure's.
            altering = true;
            naming = "list";
          } else if (altering && (word.text === "TO" || word.text === "PROCEED")) {
            naming = "list";
          } else if (word.text !== "IS") {
            altering = false;
            naming =
              word.text === "PERFORM" ||
              // THRU names a procedure where it follows one; between two data items it is a range.
              (THRU_WORDS.has(word.text) && named !== null) ||
              (word.text === "TO" && previous === "PROCEED")
                ? "one"
                : word.text === "GO" || (word.text === "TO" && previous === "GO")
                  ? "list"
                  : word.text === "PROCEDURE" && (previous === "INPUT" || previous === "OUTPUT")
                    ? "one"
                    : null;
          }
        }
      }
    }
    lastCode = line;
  }
  if (previousWasComment && lines.length > commentRunStart) {
    folds.push({ start: commentRunStart, end: lines.length, comment: true });
  }
  closeDivision();

  /** Closes the nearest open block of the verb; the blocks opened inside it are let go. */
  function closeBlock(verb: string, line: number, firstOnLine: boolean): void {
    for (let depth = blocks.length - 1; depth >= 0; depth--) {
      const block = blocks[depth] as Block;
      if (block.verb === verb) {
        blocks.length = depth;
        // A terminator that starts its line stays in view under the folded block.
        const end = firstOnLine ? line - 1 : line;
        if (end > block.line) {
          folds.push({ start: block.line, end, comment: false });
        }
        return;
      }
    }
  }

  // ---- references: resolved once every definition is known, so a forward reference counts
  const byKey = new Map<string, OutlineNode>();
  const byName = new Map<string, OutlineNode[]>();
  for (const node of nodes) {
    if (!byKey.has(node.key)) {
      byKey.set(node.key, node);
    }
    if (node.procedure) {
      const named = byName.get(node.name);
      if (named === undefined) {
        byName.set(node.name, [node]);
      } else {
        named.push(node);
      }
    }
  }
  const refs: OutlineRef[] = [];
  for (const candidate of candidates) {
    const named = byName.get(candidate.name);
    if (named === undefined) {
      continue;
    }
    const target =
      (candidate.qualifier === null
        ? undefined
        : named.find((node) => node.section === candidate.qualifier)) ??
      named.find((node) => node.kind === "paragraph" && node.section === candidate.section) ??
      (named[0] as OutlineNode);
    refs.push({
      line: candidate.line,
      startColumn: candidate.start + 1,
      endColumn: candidate.start + candidate.name.length + 1,
      key: target.key,
    });
  }

  return { nodes, folds, refs, byKey, defaultRoot: rootKey };
}

/** The key of the section or paragraph named at a position of the text, or null. */
export function resolveAt(outline: CobolOutline, line: number, column: number): string | null {
  const ref = outline.refs.find(
    (each) => each.line === line && column >= each.startColumn && column <= each.endColumn,
  );
  return ref?.key ?? null;
}
