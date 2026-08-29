/**
 * The codepage vocabulary the GUI offers and the aliases it accepts.
 *
 * Decoding itself belongs to the engine (`decode`), which is the only side that can read EBCDIC
 * CP930/CP939; Node's TextDecoder cannot. This module only normalises the names that travel between
 * the settings screen, the engine arguments and the values recorded in SOURCE.codepage.
 */

/** A codepage the GUI can offer for manual selection. */
export interface CodepageChoice {
  /** The value handed to the engine (--codepage). */
  readonly value: string;
  /** How the name is written in the interface. */
  readonly label: string;
}

/** Selectable codepages, in the order the settings screen lists them. */
export const CODEPAGES: readonly CodepageChoice[] = [
  { value: "Shift_JIS", label: "Shift_JIS" },
  { value: "UTF-8", label: "UTF-8" },
  { value: "IBM930", label: "EBCDIC CP930" },
  { value: "IBM939", label: "EBCDIC CP939" },
];

/**
 * Maps a recorded or hand-entered name onto one of the known codepages, ignoring case and the usual
 * separator and alias differences. Unknown names return null: the caller then shows the raw value
 * rather than guessing an encoding.
 */
export function normalizeCodepage(codepage: string | null | undefined): string | null {
  if (codepage === null || codepage === undefined) {
    return null;
  }
  const key = codepage.trim().toUpperCase().replace(/[_\s-]/g, "");
  switch (key) {
    case "SHIFTJIS":
    case "SJIS":
    case "MS932":
    case "CP932":
    case "WINDOWS31J":
      return "Shift_JIS";
    case "UTF8":
      return "UTF-8";
    case "IBM930":
    case "CP930":
    case "X-IBM930":
    case "XIBM930":
      return "IBM930";
    case "IBM939":
    case "CP939":
    case "XIBM939":
      return "IBM939";
    default:
      return null;
  }
}

/** The display name of a recorded codepage. Unknown values are shown as they were recorded. */
export function codepageLabel(codepage: string | null | undefined, unknownLabel: string): string {
  const normalized = normalizeCodepage(codepage);
  if (normalized === null) {
    return codepage === null || codepage === undefined || codepage === "" ? unknownLabel : codepage;
  }
  return CODEPAGES.find((choice) => choice.value === normalized)?.label ?? normalized;
}

/** Whether the codepage is one of the EBCDIC pair, which carry shift-out/shift-in control bytes. */
export function isEbcdic(codepage: string | null | undefined): boolean {
  const normalized = normalizeCodepage(codepage);
  return normalized === "IBM930" || normalized === "IBM939";
}
