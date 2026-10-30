/**
 * Reading source text out of a copy of a terminal emulator screen.
 *
 * Where a file cannot be transferred off the host, the screen itself is copied: an ISPF edit, view
 * or browse panel, or an SEU display. The copy carries what the panel draws around the source — the
 * title and command rows, the line-number field to the left of every line, the scale and marker
 * rows, the key legend — and, where one screen after another was pasted, the lines two screens
 * share. This module takes those away and puts each line back at the column the panel says it
 * starts at.
 *
 * A panel is recognised by its title row, and an SEU display also by the shape of its numbered
 * rows. Other text with no title row is left to the caller's column range unless a kind is forced:
 * a numbered COBOL source pasted as it stands looks exactly like the rows of an edit panel, and
 * guessing would shift it by a column.
 */

import { AREA_COLUMNS, SEQUENCE_COLUMNS } from "./columns";
import { clipLine, displayWidth, expandTabs, splitLines } from "./importModel";

export type ScreenKind = "ispf-edit" | "ispf-browse" | "seu" | "plain";

export interface ScreenPaste {
  readonly kind: ScreenKind;
  /** The source lines, each starting at column 1 of the record. Empty when the kind is plain. */
  readonly lines: readonly string[];
  /** The record column the first screen's data window starts at. */
  readonly firstColumn: number;
  readonly removed: {
    /** Rows the panel draws around the source. */
    readonly chrome: number;
    /** Lines a later screen repeated with the same text. */
    readonly duplicates: number;
  };
  /** Lines a later screen repeated with different text. The later text is the one kept. */
  readonly conflicts: number;
  /** Screen rows whose number field held a flag, a label or a line command; a line shown that way on two screens counts twice. */
  readonly unnumbered: number;
  /** Lines the edit panel had excluded from display and no pasted screen shows: what the copy lacks. */
  readonly hidden: number;
  /** The panel kinds the screens were read as, in the order they first appear; each kind's lines form one block. */
  readonly kinds: readonly PanelKind[];
}

export type PanelKind = Exclude<ScreenKind, "plain">;

interface Segment {
  readonly kind: PanelKind;
  /**
   * Whether a title row gave the extent of its data window: its own, or that of another screen of
   * its kind. A segment without one takes its geometry from the text.
   */
  readonly titled: boolean;
  /** The record column of the first cell of the data window. */
  readonly first: number;
  /** The width of the data window in cells. */
  readonly width: number;
  /** Browse only: the line number of the first row of the data area, or null when unknown. */
  readonly topLine: number | null;
  readonly rows: string[];
}

/** One row of a screen that holds a source line. */
interface Row {
  readonly kind: "row";
  /** The line's number, which identifies the same line on two screens; null where the field held none. */
  readonly key: string | null;
  /** The number field showed the key, whole or as what a line command left of it; an inferred key was shown by none. */
  readonly shown: boolean;
  /** The line as the row shows it, from column 1 of the record. */
  readonly text: string;
  /**
   * The record columns the row covers: its window, and the sequence area too where the number field
   * of a window that starts at the indicator shows the row's number.
   */
  readonly from: number;
  readonly to: number;
  /** The editor marks the row as a line just inserted, which no earlier screen can have numbered. */
  readonly inserted: boolean;
  /**
   * The number field holds a mark that leaves the text as it was — a label, an error flag or a line
   * command — so the row can repeat a numbered line by its text.
   */
  readonly kept: boolean;
  /**
   * Set where the number is a guess from the row's place at the edge of its screen: the row as it
   * reads where the line of that number shows other text.
   */
  readonly withoutNumber?: Row;
}

/** Lines the panel left out of the display at this place. */
interface Skip {
  readonly kind: "skip";
  readonly count: number;
  /** The numbers of the lines left out, where a regular numbering and a numbered row beside the place give them. */
  readonly numbers: readonly number[] | null;
}

type Item = Row | Skip;

/** The rows of one screen, in the order the screen shows them. */
interface Screen {
  readonly kind: PanelKind;
  readonly items: Item[];
  /** The window starts at the indicator and the number field shows the sequence area. */
  readonly fill: boolean;
}

/** One source line as the screens read so far give it. */
interface Line {
  key: string | null;
  /** A screen showed the key in its number field. */
  shown: boolean;
  text: string;
  /** The record columns the screens so far covered. */
  from: number;
  to: number;
  /** The key is a guess from the place of a row at the edge of a screen; a row of that number with other text takes it. */
  tentative: boolean;
  /** The line came from a row the editor marked as just inserted and has no number yet. */
  inserted: boolean;
}

/** A place where a screen left lines out, between the two lines its neighbouring rows became. */
interface Hole {
  readonly before: Line | null;
  readonly after: Line | null;
  readonly count: number;
  readonly kind: PanelKind;
  readonly numbers: readonly number[] | null;
}

interface Merged {
  duplicates: number;
  conflicts: number;
  readonly holes: Hole[];
}

interface Tally {
  chrome: number;
  unnumbered: number;
}

/** The record column of the indicator: a window the editor moved past the sequence numbers starts there. */
const INDICATOR_COLUMN = AREA_COLUMNS[0];

/** The last column of area B, where the edit window COBOL numbering gives ends. */
const STATEMENT_END = AREA_COLUMNS[3] - 1;

/** A width for a window whose extent the copy does not state. */
const UNBOUNDED = 1000;

/** The share of the filled rows that has to show a shape for the text to be read as that shape. */
const SHAPE_SHARE = 0.6;

/** Older ISPF releases show the window's columns with three digits. */
const EDIT_TITLE = /^\s*(?:EDIT|VIEW)\s+\S.*\bColumns\s+(\d{3,5})\s+(\d{3,5})\s*$/i;
const BROWSE_TITLE = /^\s*BROWSE\s+\S.*\bLine\s+(\d+)\s+Col\s+(\d+)\s+(\d+)\s*$/i;
const SEU_TITLE = /^\s*Columns\s*[. ]*:\s*(\d+)\s+(\d+)\s+(?:Edit|Browse)\b/i;
/**
 * A title row whose right-hand part holds a short message where the window's extent stands, as after
 * a FIND: the panel's name, then a data set name.
 */
const MESSAGE_TITLE =
  /^\s*(EDIT|VIEW|BROWSE)\s+[A-Z#$@][\w#$@-]*(?:\.[A-Z#$@][\w#$@-]*)+(?:\([\w#$@+-]+\))?(?:\s|$)/i;

const COMMAND_ROW = /^\s*(?:Command|Scroll)\s*===>|^\s*SEU==>/i;
/** The format line SEU draws under its command line. */
const FORMAT_ROW = /^\s*FMT\s/i;
const KEY_LEGEND = /^\s*P?F\d{1,2}=\S/;
const ACTION_BAR = /^\s*(?:File|Menu)\s{2,}\S+(?:\s{2,}\S+)+\s*$/;
const SEPARATOR = /^\s*[-_─━]{10,}\s*$/;
const BROWSE_MARKER = /^\s*\*{3,}\s*(Top|Bottom) of Data\s*\*{3,}\s*$/i;
/** The column-identification line of a browse display (the COLS command): it stands above the data. */
const BROWSE_SCALE = /^\s*\d?(?:[-+]+\d){3,}[-+\d]*\s*$/;
/** The marker for lines left out of the display: dashes in the number field, then the count. */
const HIDDEN_FIELD = /^ *-[ -]*$/;
const HIDDEN_LINES = /(\d+)\s+Line\(s\) not Displayed/i;

const EDIT_NUMBER = /^\d{6}$/;
const SEU_NUMBER = /^\d{4}\.\d{2}$/;
/**
 * An edit line command typed over the number field, still waiting for Enter: the command, perhaps a
 * blank, then digits, which are a count it carries and what is left of the number, then blanks where
 * the field was erased.
 */
const EDIT_COMMAND =
  /^(?:CC?|MM?|DD?|RR?|I|A|B|OO?|XX?|S|F|L|T[SEFJ]|UCC?|LCC?|MDD?|COLS|BNDS|TABS|MASK|\(\(?|\)\)?|<<?|>>?) *(\d*) *$/i;
/** A label the user gave a line: it takes the place of the number. */
const EDIT_LABEL = /^\.[A-Za-z][A-Za-z0-9]{0,4} *$/;
/**
 * An SEU line command typed over the sequence field: the command, perhaps blanks, then what is left
 * of the number, or blanks alone where the rest was erased.
 */
const SEU_COMMAND = /^[A-Za-z]{1,4} *(?:([\d.]{2,}) *)?$/;
/** Number fields the edit panel writes on rows of its own: the scale, messages, notes, profile and bounds rows, the data markers. */
const PANEL_FIELD = /^(?:==?[A-Z]+[>=]|\*+)$/;
/** Number fields that flag a line of data without numbering it. */
const EDIT_FLAGS = new Set(["==CHG>", "==ERR>", "''''''", "......"]);
/** The number field of a line the editor has just inserted, in either editor. */
const INSERTED = /^(?:'+|\.+)$/;
/** A fixed-format record as a window from column 1 shows it: sequence area, indicator, statement. */
const RECORD_SHAPE = new RegExp(`^[ \\d]{${SEQUENCE_COLUMNS}}[ */\\-D]`);

/** The nearest row with text from `index` on in the direction of `step`, or an empty row. */
function filledRow(rows: readonly string[], index: number, step: 1 | -1): string {
  for (let at = index + step; at >= 0 && at < rows.length; at += step) {
    if ((rows[at] as string).trim() !== "") {
      return rows[at] as string;
    }
  }
  return "";
}

function titleOf(rows: readonly string[], index: number): Omit<Segment, "rows"> | null {
  const row = rows[index] as string;
  const edit = EDIT_TITLE.exec(row);
  if (edit !== null) {
    const first = Number(edit[1]);
    return { kind: "ispf-edit", titled: true, first, width: Number(edit[2]) - first + 1, topLine: null };
  }
  const browse = BROWSE_TITLE.exec(row);
  if (browse !== null) {
    const first = Number(browse[2]);
    return {
      kind: "ispf-browse",
      titled: true,
      first,
      width: Number(browse[3]) - first + 1,
      topLine: Number(browse[1]),
    };
  }
  const seu = SEU_TITLE.exec(row);
  if (seu !== null) {
    const first = Number(seu[1]);
    return { kind: "seu", titled: true, first, width: Number(seu[2]) - first + 1, topLine: null };
  }
  const message = MESSAGE_TITLE.exec(row);
  // A data row can read the same way; a title row stands over the command row, or under the action
  // bar and its rule.
  const above = filledRow(rows, index, -1);
  if (message !== null && (COMMAND_ROW.test(filledRow(rows, index, 1)) || ACTION_BAR.test(above) || SEPARATOR.test(above))) {
    // The window comes from another title row of the kind, or from the text.
    const kind = (message[1] ?? "").toUpperCase() === "BROWSE" ? "ispf-browse" : "ispf-edit";
    return { kind, titled: false, first: 1, width: UNBOUNDED, topLine: null };
  }
  return null;
}

/**
 * Takes the action bar and the rule under it off the end of a segment. They are drawn above the
 * title row, so in a copy of several screens they arrive as the last rows of the screen before. A
 * rule is the panel's only under an action bar; elsewhere it is a row of the screen, which can be
 * source.
 */
function dropPanelHead(rows: string[], tally: Tally): void {
  const ruled = SEPARATOR.test(rows[rows.length - 1] ?? "") && ACTION_BAR.test(rows[rows.length - 2] ?? "");
  const count = ruled ? 2 : ACTION_BAR.test(rows[rows.length - 1] ?? "") ? 1 : 0;
  rows.splice(rows.length - count, count);
  tally.chrome += count;
}

/**
 * Whether the number field sits at the left edge or one cell in, as most rows have it: the offset at
 * which more rows hold a field `reads` accepts. Null where no row holds one at either.
 */
function fieldStart(rows: readonly string[], reads: (field: string) => boolean, width: number): number | null {
  let atEdge = 0;
  let oneIn = 0;
  for (const row of rows) {
    if (reads(row.slice(0, width)) && (row[width] ?? " ") === " ") {
      atEdge += 1;
    } else if (row.startsWith(" ") && reads(row.slice(1, width + 1)) && (row[width + 1] ?? " ") === " ") {
      oneIn += 1;
    }
  }
  return atEdge + oneIn === 0 ? null : oneIn > atEdge ? 1 : 0;
}

/**
 * Whether the windows of an untitled edit copy start at column 1. They do where the text reads as
 * fixed-format records — a blank or numbered sequence area, an indicator, then the statement — or
 * as JCL; a window the editor moved past the COBOL sequence numbers starts at column 7.
 */
function startsAtColumnOne(windows: readonly string[]): boolean {
  const filled = windows.filter((window) => window.trim() !== "");
  if (filled.some((window) => window.startsWith("//"))) {
    return true;
  }
  const records = filled.filter(
    (window) => RECORD_SHAPE.test(window) && window.slice(INDICATOR_COLUMN).trim() !== "",
  );
  return filled.length > 0 && records.length / filled.length >= SHAPE_SHARE;
}

interface FieldRow {
  readonly field: string;
  readonly numbered: boolean;
  readonly window: string;
  /** Set on the row that stands where the panel left this many lines out; it holds no line itself. */
  readonly skipped?: number;
  /** What a line command typed over the number left of it. */
  readonly remains?: string;
  /** The editor's flag the field shows, also under a command or label typed over its head; null for none. */
  readonly flag?: string | null;
  /** The field holds none of the forms this module knows, and the window holds text. */
  readonly unfamiliar?: boolean;
}

interface Flag {
  readonly remains: string | null;
  readonly flag: string | null;
}

/**
 * Whether a number field marks a row of data that shows no number, what it left of the number, and
 * the editor's flag it shows, which a line command or a label typed over the head of the flag leaves
 * the rest of. Null for every other field: the row is the panel's own.
 */
function flagOf(kind: PanelKind, field: string): Flag | null {
  if (kind === "seu") {
    if (/^'+$/.test(field)) {
      return { remains: null, flag: field };
    }
    const typed = SEU_COMMAND.exec(field);
    return typed === null ? null : { remains: typed[1] ?? null, flag: null };
  }
  if (EDIT_FLAGS.has(field)) {
    return { remains: null, flag: field };
  }
  if (EDIT_LABEL.test(field)) {
    return { remains: null, flag: null };
  }
  for (const flag of EDIT_FLAGS) {
    for (let cut = 1; cut < flag.length; cut++) {
      const typed = field.slice(0, cut);
      if (field.slice(cut) === flag.slice(cut) && (EDIT_LABEL.test(typed) || EDIT_COMMAND.test(typed))) {
        return { remains: null, flag };
      }
    }
  }
  const typed = EDIT_COMMAND.exec(field);
  return typed === null ? null : { remains: typed[1] === "" ? null : (typed[1] ?? null), flag: null };
}

/**
 * The number of a row whose number field a line command was typed over. The digits left in the field
 * are the end of the number, perhaps behind a count the command carries, and the numbered rows
 * around the row give the rest: the number that lies between them. In text with no title row
 * (`again`), a row whose digits complete to the number above it is that line on the next rectangle
 * copied. Null where no reading fits.
 */
function keyFromRemains(
  remains: string,
  low: string | null,
  high: string | null,
  number: RegExp,
  again: boolean,
): string | null {
  const near = low ?? high;
  if (near === null) {
    return null;
  }
  const fits: string[] = [];
  let repeats = false;
  for (let cut = 0; remains.length - cut >= 2; cut++) {
    const tail = remains.slice(cut);
    if (tail.length > near.length) {
      continue;
    }
    const key = near.slice(0, near.length - tail.length) + tail;
    repeats ||= again && key === low;
    if (
      number.test(key) &&
      (low === null || Number(low) < Number(key)) &&
      (high === null || Number(key) < Number(high))
    ) {
      fits.push(key);
    }
  }
  fits.sort((one, other) => Number(one) - Number(other));
  // Beside the row above it, or, at the top of a screen, beside the row below.
  return (low === null ? fits[fits.length - 1] : fits[0]) ?? (repeats ? low : null);
}

/** The rows of an edit or SEU segment that hold source, with their number field cut off. */
function fieldRows(
  segment: Segment,
  start: number,
  fieldWidth: number,
  dataStart: number,
  tally: Tally,
): FieldRow[] {
  const number = segment.kind === "seu" ? SEU_NUMBER : EDIT_NUMBER;
  const found: FieldRow[] = [];
  for (const row of segment.rows) {
    if (row.trim() === "") {
      continue;
    }
    const field = row.slice(start, start + fieldWidth);
    const window = clipLine(row, dataStart + 1, dataStart + segment.width).trimEnd();
    const fieldClosed = row.slice(0, start).trim() === "" && (row[start + fieldWidth] ?? " ") === " ";
    const hidden = HIDDEN_FIELD.test(field) ? HIDDEN_LINES.exec(row.slice(start + fieldWidth)) : null;
    if (hidden !== null) {
      tally.chrome += 1;
      found.push({ field, numbered: false, window: "", skipped: Number(hidden[1]) });
    } else if (COMMAND_ROW.test(row) || FORMAT_ROW.test(row) || KEY_LEGEND.test(row)) {
      tally.chrome += 1;
    } else if (fieldClosed && number.test(field)) {
      found.push({ field, numbered: true, window });
    } else {
      const flag = fieldClosed ? flagOf(segment.kind, field) : null;
      if (flag === null) {
        // A field of an unfamiliar form over a window with text is kept until the data area is known.
        if (window === "" || PANEL_FIELD.test(field)) {
          tally.chrome += 1;
        } else {
          found.push({ field, numbered: false, window, flag: field, unfamiliar: true });
        }
      } else if (window === "" && flag.flag !== null && INSERTED.test(flag.flag)) {
        // A row marked as inserted with nothing in its window is one the editor opened for typing. A
        // flagged, labelled or command row with an empty window is a blank line of the source.
        tally.chrome += 1;
      } else {
        tally.unnumbered += 1;
        found.push({ field, numbered: false, window, remains: flag.remains ?? undefined, flag: flag.flag });
      }
    }
  }
  // Inside the data area, between the first and the last row the panel numbered or flagged, a row of
  // an unfamiliar field is a line of the source, placed by its neighbours; outside it, a row of the
  // panel's.
  let first = found.length;
  let last = -1;
  found.forEach((row, index) => {
    if (row.unfamiliar !== true && row.skipped === undefined) {
      first = Math.min(first, index);
      last = index;
    }
  });
  return found.filter((row, index) => {
    if (row.unfamiliar !== true) {
      return true;
    }
    const inside = index > first && index < last;
    if (inside) {
      tally.unnumbered += 1;
    } else {
      tally.chrome += 1;
    }
    return inside;
  });
}

function editOrSeuRows(
  segment: Segment,
  start: number,
  tally: Tally,
  headless: boolean,
): { rows: Item[]; first: number; fill: boolean } {
  const fieldWidth = segment.kind === "seu" ? 7 : 6;
  // The scale row, where the copy holds one, says exactly where the window starts.
  const scaleRow = segment.rows.find((row) => row.slice(start, start + fieldWidth) === "=COLS>");
  // The scale opens with "-", or with "+" or a digit where the window starts on a multiple of five.
  const scale = scaleRow === undefined ? -1 : scaleRow.slice(start + fieldWidth).search(/\S/);
  const dataStart = scale >= 0 ? start + fieldWidth + scale : start + fieldWidth + 1;

  const found = fieldRows(segment, start, fieldWidth, dataStart, tally);
  const first =
    segment.titled || segment.kind === "seu"
      ? segment.first
      : startsAtColumnOne(found.filter((row) => row.skipped === undefined).map((row) => row.window))
        ? 1
        : INDICATOR_COLUMN;

  const number = segment.kind === "seu" ? SEU_NUMBER : EDIT_NUMBER;
  const keys: (string | null)[] = found.map((row) => (row.numbered ? row.field : null));
  found.forEach((row, index) => {
    if (row.remains !== undefined) {
      const low = keys.slice(0, index).reverse().find((key) => key !== null) ?? null;
      const high = found.slice(index + 1).find((later) => later.numbered)?.field ?? null;
      keys[index] = keyFromRemains(row.remains, low, high, number, headless);
    }
  });

  const to = first + segment.width - 1;
  const rows: Item[] = found.map((row, index): Item => {
    if (row.skipped !== undefined) {
      return { kind: "skip", count: row.skipped, numbers: null };
    }
    const key = keys[index] ?? null;
    return {
      kind: "row",
      key,
      shown: key !== null,
      text: (" ".repeat(first - 1) + row.window).trimEnd(),
      from: first,
      to,
      inserted: key === null && row.flag !== undefined && row.flag !== null && INSERTED.test(row.flag),
      // A label, an error flag or a line command marks the line and leaves its text as it was.
      kept: row.flag === null || row.flag === "==ERR>",
    };
  });
  // A window from the indicator to the end of area B is the one COBOL numbering gives, and its
  // number field shows the sequence area.
  const fill =
    segment.kind === "ispf-edit" &&
    first === INDICATOR_COLUMN &&
    (!segment.titled || segment.first + segment.width - 1 === STATEMENT_END);
  return { rows, first, fill };
}

/** Whether the row is the column-identification line of a browse display: the row under the command row. */
function isScale(rows: readonly string[], index: number): boolean {
  return BROWSE_SCALE.test(rows[index] ?? "") && COMMAND_ROW.test(rows[index - 1] ?? "");
}

/**
 * The screens of a browse segment. A segment whose title row gave no line number can hold one
 * screen after another: a command row or a Top of Data marker that follows rows of data opens the
 * next one.
 */
function browseScreens(segment: Segment, tally: Tally): Item[][] {
  const parts: string[][] = [[]];
  let opened = false;
  segment.rows.forEach((row, index) => {
    const command = COMMAND_ROW.test(row);
    const top = (BROWSE_MARKER.exec(row)?.[1] ?? "").toLowerCase() === "top";
    if (segment.topLine === null && opened && (command || top)) {
      parts.push([]);
      opened = false;
    }
    (parts[parts.length - 1] as string[]).push(row);
    opened ||= !(command || top || row.trim() === "" || isScale(segment.rows, index));
  });
  return parts.map((part) => browseRows(segment, part, tally));
}

/**
 * Whether the row is a row of the key legend: two or more key settings, or one directly under a
 * command row the panel puts at the bottom. A single setting elsewhere can be a line of the data.
 */
function isLegend(rows: readonly string[], index: number): boolean {
  const row = rows[index] ?? "";
  return (
    KEY_LEGEND.test(row) &&
    ((row.match(/(?:^|\s)P?F\d{1,2}=\S/g) ?? []).length >= 2 || COMMAND_ROW.test(rows[index - 1] ?? ""))
  );
}

function browseRows(segment: Segment, screen: readonly string[], tally: Tally): Item[] {
  // The key legend stands in the block after the data, with blank rows and a command row the panel
  // puts at the bottom.
  const trailing = (index: number): boolean =>
    (screen[index] ?? "").trim() === "" || COMMAND_ROW.test(screen[index] ?? "") || isLegend(screen, index);
  let after = screen.length;
  while (after > 0 && trailing(after - 1)) {
    after -= 1;
  }
  const rows: Item[] = [];
  let offset = 0;
  for (let index = 0; index < screen.length; index++) {
    const row = screen[index] as string;
    // The scale takes a row of the screen and no line of the data, so the offset stays where it is.
    if (COMMAND_ROW.test(row) || (index >= after && isLegend(screen, index)) || isScale(screen, index)) {
      tally.chrome += 1;
      continue;
    }
    const marker = BROWSE_MARKER.exec(row);
    if (marker !== null) {
      tally.chrome += 1;
      if ((marker[1] ?? "").toLowerCase() === "bottom") {
        break;
      }
      offset += 1;
      continue;
    }
    const key = segment.topLine === null ? null : String(segment.topLine + offset);
    offset += 1;
    rows.push({
      kind: "row",
      key,
      shown: key !== null,
      text: (" ".repeat(segment.first - 1) + clipLine(row, 1, segment.width)).trimEnd(),
      from: segment.first,
      to: segment.first + segment.width - 1,
      inserted: false,
      kept: true,
    });
  }
  return rows;
}

/**
 * Whether a row holds the text of a line in the record columns both cover. A row that shows a flag
 * in a window from the indicator covers the window alone, so the sequence area stays out of it.
 */
function sameColumns(line: Line, row: Row): boolean {
  if (line.from === row.from && line.to === row.to) {
    // Both cover the same columns, so the texts compare as they stand.
    return line.text === row.text;
  }
  const from = Math.max(line.from, row.from);
  const to = Math.min(line.to, row.to);
  return clipLine(line.text, from, to).trimEnd() === clipLine(row.text, from, to).trimEnd();
}

function padCells(text: string, width: number): string {
  return text + " ".repeat(Math.max(0, width - displayWidth(text)));
}

/** Puts the row's columns into the line; the line keeps what it has on either side of them. */
function overlay(line: Line, row: Row): void {
  const ahead = clipLine(line.text, 1, row.from - 1);
  const own = clipLine(row.text, row.from, row.to);
  const behind = clipLine(line.text, row.to + 1, Infinity);
  line.text = (
    padCells(ahead, row.from - 1) +
    (behind === "" ? own : padCells(own, row.to - row.from + 1)) +
    behind
  ).trimEnd();
  line.from = Math.min(line.from, row.from);
  line.to = Math.max(line.to, row.to);
}

/** What a later screen's row does to the line it repeats: the later text is the one kept in the row's columns. */
function lay(line: Line, row: Row, result: Merged): void {
  if (sameColumns(line, row)) {
    result.duplicates += 1;
  } else {
    result.conflicts += 1;
  }
  overlay(line, row);
}

/**
 * Whether a row that shows no number and took none from the numbering repeats a line by its text.
 * Such rows come where the numbers rise unevenly, where a number guessed from the row's place at the
 * edge of its screen named a line of other text, and on a screen with no numbered row. A label, an
 * error flag or a line command leaves the line's text as it was, so such a row can repeat any line.
 * A changed row repeats only a line whose number no screen showed: a line that showed its number on
 * an earlier screen and shows the change flag on a later one was changed in between. A browse line
 * number counts lines from the top, so a browse row can repeat any browse line.
 */
function repeats(line: Line | undefined, row: Row): boolean {
  return line !== undefined && (row.kept || !line.shown) && sameColumns(line, row);
}

/** How many rows at the head of `rows` repeat, by their text, the lines that end at `end`. */
function sharedByText(lines: readonly Line[], end: number, rows: readonly Row[]): number {
  let shared = Math.min(end, rows.length);
  for (; shared > 0; shared--) {
    const from = end - shared;
    if (rows.slice(0, shared).every((row, index) => repeats(lines[from + index], row))) {
      break;
    }
  }
  return shared;
}

/**
 * Where a screen with no numbered row repeats a run of the lines whole: the last such place, taking
 * first a run whose lines have no number or a guessed one. -1 where no run repeats them all.
 */
function runAt(lines: readonly Line[], rows: readonly Row[]): number {
  let numbered = -1;
  for (let at = lines.length - rows.length; at >= 0 && rows.length > 0; at--) {
    const run = lines.slice(at, at + rows.length);
    if (rows.every((row, index) => repeats(run[index], row))) {
      if (run.every((line) => line.key === null || line.tentative)) {
        return at;
      }
      numbered = numbered < 0 ? at : numbered;
    }
  }
  return numbered;
}

/**
 * Lays one screen over the lines the screens before it gave.
 *
 * `lines` are those of the screen's panel kind alone. A numbered row is the line of that number:
 * where the lines hold it already it is kept once, with the later text in the columns the row covers
 * where the two copies differ, and where they hold no such number it goes in ahead of the first
 * greater number, so a screen copied out of turn, or after lines were excluded from the display,
 * lands where its numbers say. A number guessed from a row's place at the edge of its screen only
 * finds a line: where that line holds other text, the row is placed as one with no number, and a
 * numbered row that meets a line with a guessed number and other text takes the number from it.
 *
 * A row that shows no number and took none from the numbering is placed by the numbered
 * rows around it: it stands for the line at its place between them. A row the editor marks as newly
 * inserted is a line of its own unless the line at its place has its text. Rows ahead of the first
 * numbered row count back from it, up to a place where an earlier screen left lines out, and rows
 * of a screen with no numbered row at all repeat the last lines only by their text.
 */
function mergeScreen(lines: Line[], screen: readonly Item[], result: Merged, kind: PanelKind): void {
  const next = screen.map((item) => {
    if (item.kind !== "row" || item.withoutNumber === undefined) {
      return item;
    }
    const line = lines.find((one) => one.key === item.key);
    return line === undefined || sameColumns(line, item) ? item : item.withoutNumber;
  });
  const rowAt = (index: number): Row | null => {
    const item = next[index];
    return item !== undefined && item.kind === "row" ? item : null;
  };
  const anchors: number[] = [];
  const unnumbered: number[] = [];
  next.forEach((item, index) => {
    if (item.kind === "row") {
      (item.key === null ? unnumbered : anchors).push(index);
    }
  });
  /** The line each row of the screen became. */
  const held: (Line | null)[] = next.map(() => null);
  const add = (index: number, at: number): void => {
    const row = next[index] as Row;
    const line: Line = {
      key: row.key,
      shown: row.shown,
      text: row.text,
      from: row.from,
      to: row.to,
      tentative: row.withoutNumber !== undefined,
      inserted: row.inserted,
    };
    lines.splice(at, 0, line);
    held[index] = line;
  };
  const over = (index: number, line: Line): void => {
    const row = next[index] as Row;
    lay(line, row, result);
    line.tentative &&= row.key === null || row.withoutNumber !== undefined;
    line.shown ||= row.shown;
    held[index] = line;
  };
  /** Lays the rows that repeat, by text, the lines ending at `end` over them, and adds the rest there. */
  const byText = (indices: readonly number[], end: number): void => {
    const shared = sharedByText(lines, end, indices.map((index) => next[index] as Row));
    indices.forEach((index, order) => {
      if (order < shared) {
        over(index, lines[end - shared + order] as Line);
      } else {
        add(index, end + order - shared);
      }
    });
  };

  const whole = anchors.length === 0 ? runAt(lines, unnumbered.map((index) => next[index] as Row)) : -1;
  if (whole >= 0) {
    // The screen repeats a run of the lines, wherever among them it stands.
    unnumbered.forEach((index, order) => over(index, lines[whole + order] as Line));
  } else if (anchors.length === 0) {
    // A browse screen copied with no title can end on the emulator's blank rows. Where the next
    // screen repeats the lines ahead of them, they stood between the two and are dropped.
    let blank = lines.length;
    while (kind === "ispf-browse" && blank > 0 && (lines[blank - 1] as Line).key === null && (lines[blank - 1] as Line).text === "") {
      blank -= 1;
    }
    if (blank < lines.length && sharedByText(lines, blank, unnumbered.map((index) => next[index] as Row)) > 0) {
      lines.splice(blank);
    }
    byText(unnumbered, lines.length);
  } else {
    // The numbered rows first: each is found among the lines or takes its place among them.
    let firstFound = false;
    let cursor = 0;
    anchors.forEach((index, order) => {
      const row = next[index] as Row;
      let at = lines.findIndex((line) => line.key === row.key);
      const guessed = lines[at];
      if (guessed !== undefined && guessed.tentative && !sameColumns(guessed, row)) {
        // The line's number was guessed from its place, and the line of that number reads otherwise.
        guessed.key = null;
        guessed.shown = false;
        guessed.tentative = false;
        at = -1;
      }
      let found = at >= 0;
      if (found) {
        over(index, lines[at] as Line);
      } else {
        const value = keyValue(row.key as string);
        let end = lines.findIndex(
          (line, position) => position >= cursor && line.key !== null && keyValue(line.key) > value,
        );
        end = end < 0 ? lines.length : end;
        let from = end;
        while (from > cursor && (lines[from - 1] as Line).key === null) {
          from -= 1;
        }
        // After RESET a line shown with a flag before comes back under its number. That reading
        // needs a witness, or a line of the same text that follows a flagged one would be lost: the
        // numbered row beside it on this screen has to be the numbered line beside the flagged
        // lines.
        const before = rowAt(index - 1);
        const after = rowAt(index + 1);
        const witnessed =
          (after !== null && after.key !== null && lines[end]?.key === after.key) ||
          (before !== null && before.key !== null && from > 0 && lines[from - 1]?.key === before.key);
        // A line typed in on an earlier screen takes its number when Enter is pressed: where it is the
        // last line so far, the first numbered row of the next screen, greater than every number,
        // with its text is that line.
        const last = lines[lines.length - 1];
        const entered =
          order === 0 && end === lines.length && last !== undefined && last.inserted && sameColumns(last, row);
        let same = entered ? lines.length - 1 : -1;
        for (let position = from; witnessed && position < end && same < 0; position++) {
          same = sameColumns(lines[position] as Line, row) ? position : -1;
        }
        if (same >= 0) {
          const line = lines[same] as Line;
          line.key = row.key;
          line.shown ||= row.shown;
          line.tentative = row.withoutNumber !== undefined;
          line.inserted = false;
          overlay(line, row);
          result.duplicates += 1;
          held[index] = lines[same] as Line;
          at = same;
          found = true;
        } else {
          add(index, end);
          at = end;
        }
      }
      firstFound ||= order === 0 && found;
      cursor = at + 1;
    });

    // The rows ahead of the first numbered one.
    const first = lines.indexOf(held[anchors[0] as number] as Line);
    const ahead = next.slice(0, anchors[0]).map((_, index) => index);
    if (firstFound) {
      // The numbered row repeats a line, so each row ahead of it stands for the line that far
      // ahead, as far back as the lines run on without a place where lines were left out.
      let at = first - 1;
      for (const index of [...ahead].reverse()) {
        const item = next[index] as Item;
        if (item.kind === "skip") {
          at -= item.count;
          continue;
        }
        const line = lines[at];
        const joined = line !== undefined && !result.holes.some((hole) => hole.after === lines[at + 1]);
        if (joined && (!item.inserted || sameColumns(line, item))) {
          over(index, line);
          at -= 1;
        } else {
          add(index, Math.max(at + 1, 0));
        }
      }
    } else {
      // The numbered row is new to the lines, so what is ahead of it repeats lines only by text.
      byText(
        ahead.filter((index) => rowAt(index) !== null),
        first,
      );
    }

    // The rows after each numbered one, up to the next numbered one or the end of the screen.
    anchors.forEach((index, order) => {
      const last = anchors[order + 1] ?? next.length;
      let at = lines.indexOf(held[index] as Line) + 1;
      const following = anchors[order + 1] === undefined ? null : held[anchors[order + 1] as number];
      let end = Math.max(following === null ? lines.length : lines.indexOf(following as Line), at);
      for (let position = index + 1; position < last; position++) {
        const item = next[position] as Item;
        if (item.kind === "skip") {
          at = Math.min(end, at + item.count);
        } else if (at < end && (!item.inserted || sameColumns(lines[at] as Line, item))) {
          over(position, lines[at] as Line);
          at += 1;
        } else {
          add(position, at);
          at += 1;
          end += 1;
        }
      }
    });
  }

  // Where this screen left lines out, between the lines its rows on either side became.
  next.forEach((item, index) => {
    if (item.kind !== "skip") {
      return;
    }
    const before = held.slice(0, index).reverse().find((line) => line !== null) ?? null;
    const after = held.slice(index + 1).find((line) => line !== null) ?? null;
    if (!result.holes.some((hole) => hole.kind === kind && hole.before === before && hole.after === after)) {
      result.holes.push({ before, after, count: item.count, kind, numbers: item.numbers });
    }
  });
}

/**
 * The lines of one panel kind the screens left out of their display and no screen showed: what the
 * copy lacks. Where the numbering gives a place's lines their numbers, each number no line holds
 * counts once, however many screens left it out. Elsewhere a place counts its lines less those the
 * screens put there.
 */
function missingLines(lines: readonly Line[], holes: readonly Hole[]): number {
  const shown = new Set(lines.flatMap((line) => (line.key === null ? [] : [keyValue(line.key)])));
  const absent = new Set<number>();
  let missing = 0;
  for (const hole of holes) {
    if (hole.numbers !== null) {
      for (const value of hole.numbers.filter((one) => !shown.has(one))) {
        absent.add(value);
      }
    } else {
      missing += Math.max(0, hole.count - linesAt(lines, holes, hole));
    }
  }
  return missing + absent.size;
}

/**
 * How many lines the screens put where a place left lines out: those between its two lines, or, at
 * the edge of a screen, those another screen put directly before the line after the place (or after
 * the line before it), back to the next place lines were left out or the end of the lines.
 */
function linesAt(lines: readonly Line[], holes: readonly Hole[], hole: Hole): number {
  if (hole.before !== null && hole.after !== null) {
    return lines.indexOf(hole.after) - lines.indexOf(hole.before) - 1;
  }
  // Each other place stands between two lines: ahead of the index this gives.
  const places = holes
    .filter((other) => other !== hole)
    .flatMap((other) => [
      ...(other.after === null ? [] : [lines.indexOf(other.after)]),
      ...(other.before === null ? [] : [lines.indexOf(other.before) + 1]),
    ]);
  if (hole.after !== null) {
    const at = lines.indexOf(hole.after);
    return at - Math.max(0, ...places.filter((place) => place <= at));
  }
  if (hole.before !== null) {
    const at = lines.indexOf(hole.before);
    return Math.min(lines.length, ...places.filter((place) => place > at)) - at - 1;
  }
  return 0;
}

/** The value of a line number as a whole number: an SEU number counts in hundredths. */
function keyValue(key: string): number {
  return Number(key.replace(".", ""));
}

/** The number field of an edit or SEU panel that shows `value`, or null where the field cannot hold it. */
function keyOf(kind: PanelKind, value: number): string | null {
  const digits = String(value).padStart(6, "0");
  const key = kind === "seu" ? `${digits.slice(0, -2)}.${digits.slice(-2)}` : digits;
  return value > 0 && (kind === "seu" ? SEU_NUMBER : EDIT_NUMBER).test(key) ? key : null;
}

/**
 * The step by which the numbers of the edit and SEU screens rise, where every two numbered rows that
 * stand next to each other differ by the same one. Null where they differ by more than one step or
 * no two numbered rows stand together.
 */
function regularIncrement(screens: readonly Screen[]): number | null {
  const steps = new Set<number>();
  for (const screen of screens.filter((one) => one.kind !== "ispf-browse")) {
    screen.items.forEach((item, index) => {
      const next = screen.items[index + 1];
      if (item.kind === "row" && item.key !== null && next?.kind === "row" && next.key !== null) {
        steps.add(keyValue(next.key) - keyValue(item.key));
      }
    });
  }
  return steps.size === 1 ? ([...steps][0] as number) : null;
}

/**
 * Gives the rows of an edit or SEU screen that show no number the number their place gives them,
 * where the numbers rise by a regular `increment`: after numbered row K the j-th row is
 * K + j·increment, and rows ahead of the first numbered row count back from it. The rows between two
 * numbered rows take numbers only where they and the lines left out among them fill exactly the
 * numbers between the two; rows around a line just inserted keep none. The numbers of rows ahead of
 * the first numbered row or after the last are guesses, which the merge checks against the lines'
 * text. A place where lines were left out takes the numbers after the numbered row before it, or
 * before the one after it. Where the number field shows the sequence area, a numbered row has its
 * number there.
 */
function numberRows(screen: Screen, increment: number | null): Item[] {
  const items = screen.items;
  const keys = items.map((item) => (item.kind === "row" ? item.key : null));
  const guessed = items.map(() => false);
  const anchors = keys.flatMap((key, index) => (key === null ? [] : [index]));
  const bounds = [-1, ...anchors, items.length];
  for (let gap = 0; increment !== null && anchors.length > 0 && gap + 1 < bounds.length; gap++) {
    const low = bounds[gap] as number;
    const high = bounds[gap + 1] as number;
    const between = items.slice(low + 1, high);
    if (between.length === 0 || between.some((item) => item.kind === "row" && item.inserted)) {
      continue;
    }
    const span = between.reduce((sum, item) => sum + (item.kind === "skip" ? item.count : 1), 0);
    const base =
      low >= 0 ? keyValue(keys[low] as string) : keyValue(keys[high] as string) - (span + 1) * increment;
    if (low >= 0 && high < items.length && keyValue(keys[high] as string) !== base + (span + 1) * increment) {
      continue;
    }
    let value = base;
    const given = between.map((item) => {
      value += (item.kind === "skip" ? item.count : 1) * increment;
      return item.kind === "skip" ? undefined : keyOf(screen.kind, value);
    });
    if (given.every((key) => key !== null)) {
      given.forEach((key, offset) => {
        keys[low + 1 + offset] = key ?? null;
        guessed[low + 1 + offset] = low < 0 || high === items.length;
      });
    }
  }
  return items.map((item, index): Item => {
    if (item.kind === "skip") {
      const before = keys[index - 1] ?? null;
      const after = keys[index + 1] ?? null;
      const first =
        increment === null
          ? null
          : before !== null
            ? keyValue(before) + increment
            : after !== null
              ? keyValue(after) - item.count * increment
              : null;
      const numbers =
        first === null || increment === null
          ? null
          : Array.from({ length: item.count }, (_, offset) => first + offset * increment);
      return { ...item, numbers };
    }
    const key = keys[index] ?? null;
    const numbered: Row =
      key !== null && screen.fill
        ? { ...item, key, text: (key + item.text.slice(SEQUENCE_COLUMNS)).trimEnd(), from: 1 }
        : { ...item, key };
    return guessed[index] ? { ...numbered, withoutNumber: item } : numbered;
  });
}

/**
 * The parts of one screen's rows in which the numbers rise. Text with no title row can hold one
 * screen after another, and a number that falls back is where the next one starts.
 */
function risingRuns(items: readonly Item[]): Item[][] {
  const runs: Item[][] = [[]];
  let last: number | null = null;
  for (const item of items) {
    if (item.kind === "row" && item.key !== null) {
      const value = Number(item.key);
      if (last !== null && value <= last) {
        runs.push([]);
      }
      last = value;
    }
    (runs[runs.length - 1] as Item[]).push(item);
  }
  return runs;
}

const PLAIN: ScreenPaste = {
  kind: "plain",
  lines: [],
  firstColumn: 1,
  removed: { chrome: 0, duplicates: 0 },
  conflicts: 0,
  unnumbered: 0,
  hidden: 0,
};

/**
 * Reads the source out of pasted screen text. `forced` names the panel for text that carries no
 * title row, such as a rectangle copied out of the middle of a screen.
 */
export function normalizeScreenPaste(text: string, forced?: PanelKind): ScreenPaste {
  const tally: Tally = { chrome: 0, unnumbered: 0 };
  const all = splitLines(text).map(expandTabs);

  const head: string[] = [];
  const titled: Segment[] = [];
  for (const row of all) {
    const title = titleOf(row);
    if (title !== null) {
      dropPanelHead(titled.length === 0 ? head : (titled[titled.length - 1] as Segment).rows, tally);
      titled.push({ ...title, rows: [] });
      tally.chrome += 1;
    } else if (titled.length === 0) {
      head.push(row);
    } else {
      (titled[titled.length - 1] as Segment).rows.push(row);
    }
  }

  const filled = all.filter((row) => row.trim() !== "");
  const seuShaped =
    filled.length > 0 &&
    filled.filter((row) => /^ ?\d{4}\.\d{2}(?: |$)/.test(row)).length / filled.length >= SHAPE_SHARE;
  const titledKind = titled.length > 0 ? titled[0].kind : undefined;
  const kind: ScreenKind = forced ?? titledKind ?? (seuShaped ? "seu" : "plain");
  if (kind === "plain") {
    return PLAIN;
  }

  // What precedes the first title row is a screen copied without it, and a title row can hold a
  // message where the window's extent stands. Such a screen takes the geometry of the first titled
  // screen of its kind, or, where there is none, what the text itself shows.
  const segments: Segment[] = [
    {
      kind,
      titled: false,
      first: 1,
      width: UNBOUNDED,
      topLine: null,
      // Above a title row a blank row is the emulator's, where in forced text it is a source line.
      rows: titled.length === 0 ? head : head.filter((row) => row.trim() !== ""),
    },
    ...titled,
  ].map((segment) => {
    const model = titled.find((other) => other.kind === segment.kind && other.titled);
    return segment.titled || model === undefined
      ? segment
      : { ...segment, titled: true, first: model.first, width: model.width };
  });

  const editStart = fieldStart(all, (field) => EDIT_NUMBER.test(field), 6) ?? 0;
  const seuStart = fieldStart(all, (field) => SEU_NUMBER.test(field), 7) ?? 0;
  const screens: Screen[] = [];
  let firstColumn: number | null = null;
  for (const [index, segment] of segments.entries()) {
    let first = segment.first;
    const before = screens.length;
    if (segment.kind === "ispf-browse") {
      for (const items of browseScreens(segment, tally)) {
        screens.push({ kind: segment.kind, items, fill: false });
      }
    } else {
      const seu = segment.kind === "seu";
      const number = seu ? SEU_NUMBER : EDIT_NUMBER;
      const width = seu ? 7 : 6;
      const read = editOrSeuRows(
        segment,
        // Each screen says for itself where its number field starts: one can be copied with the
        // cell ahead of the field and the next without it. A screen with no numbered row goes by
        // where its rows read as flags, labels or commands, and failing that by the rest of the text.
        fieldStart(segment.rows, (field) => number.test(field), width) ??
          fieldStart(segment.rows, (field) => flagOf(segment.kind, field) !== null, width) ??
          (seu ? seuStart : editStart),
        tally,
        // Only the text ahead of the first title row was copied without one.
        index === 0,
      );
      for (const items of risingRuns(read.rows)) {
        screens.push({ kind: segment.kind, items, fill: read.fill });
      }
      first = read.first;
    }
    if (firstColumn === null && screens.slice(before).some((screen) => screen.items.some((item) => item.kind === "row"))) {
      firstColumn = first;
    }
  }

  // The numbers are read across the whole copy first: whether they rise by one step decides how a
  // row that shows none is placed.
  const increment = regularIncrement(screens);
  // The numbers of one panel kind count something else than those of another, so each kind's lines
  // are one block, in the order the kinds first appear.
  const blocks = new Map<PanelKind, Line[]>();
  const merged: Merged = { duplicates: 0, conflicts: 0, holes: [] };
  for (const screen of screens) {
    const block = blocks.get(screen.kind) ?? [];
    blocks.set(screen.kind, block);
    const items = screen.kind === "ispf-browse" ? screen.items : numberRows(screen, increment);
    mergeScreen(block, items, merged, screen.kind);
  }
  let hidden = 0;
  for (const [kind, block] of blocks) {
    hidden += missingLines(block, merged.holes.filter((hole) => hole.kind === kind));
  }

  const texts = [...blocks.values()].flat().map((row) => row.text);
  while (texts.length > 0 && texts[texts.length - 1] === "") {
    texts.pop();
  }
  return {
    kind,
    lines: texts,
    firstColumn: firstColumn ?? 1,
    removed: { chrome: tally.chrome, duplicates: merged.duplicates },
    conflicts: merged.conflicts,
    unnumbered: tally.unnumbered,
    hidden,
  };
}
