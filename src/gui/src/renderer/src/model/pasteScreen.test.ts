/**
 * The screens below are constructed: they are laid out from the published panel formats, and no
 * capture of a real session stands behind them. The SEU display follows the example in IBM i 7.5 "Using the COBOL Syntax Checker in SEU"
 * (ibm.com/docs/en/i/7.5.0?topic=utility-using-cobol-syntax-checker-in-seu). The edit panel follows
 * z/OS 3.1 ISPF Edit and Edit Macros, "Sequence number display"
 * (ibm.com/docs/en/zos/3.1.0?topic=numbers-sequence-number-display): the line command field repeats
 * the sequence number, and with COBOL numbering column 7 is the first column displayed. The browse
 * panel follows the Browse data display figures of z/OS 2.4 ISPF User's Guide Volume II, SC19-3628-40
 * (ibm.com/docs/en/SSLTBW_2.4.0/pdf/f54u200_v2r4.pdf): the title row reads
 * `BROWSE    name    Line 00000017 Col 001 080`, the data starts in the first screen column, and the
 * column-identification line stands above the data with no number field.
 */

import { describe, expect, it } from "vitest";
import { normalizeScreenPaste } from "./pasteScreen";

const EDIT_HEAD = [
  "   File  Edit  Edit_Settings  Menu  Utilities  Compilers  Test  Help",
  " -------------------------------------------------------------------------------",
];

function editScreen(columns: string, rows: readonly string[], scroll = "CSR"): string[] {
  return [
    ...EDIT_HEAD,
    ` EDIT       USER01.SRC.COBOL(PGM001) - 01.03                ${columns}`,
    ` Command ===>                                                  Scroll ===> ${scroll}`,
    ...rows,
  ];
}

const TOP = " ****** ***************************** Top of Data ******************************";
const BOTTOM = " ****** **************************** Bottom of Data ****************************";
const COLS = " =COLS> ----+----1----+----2----+----3----+----4----+----5----+----6----+----7--";

describe("normalizeScreenPaste", () => {
  describe("an ISPF edit panel", () => {
    it("drops the panel rows and the line-number field, and keeps the columns", () => {
      const pasted = editScreen("Columns 00001 00072", [
        TOP,
        COLS,
        " 000100        IDENTIFICATION DIVISION.",
        " 000200        PROGRAM-ID. PGM001.",
        " 000300       * A COMMENT",
        " 000400",
        " 000500        PROCEDURE DIVISION.",
        BOTTOM,
      ]).join("\r\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.kind).toBe("ispf-edit");
      expect(result.firstColumn).toBe(1);
      expect(result.lines).toEqual([
        "       IDENTIFICATION DIVISION.",
        "       PROGRAM-ID. PGM001.",
        "      * A COMMENT",
        "",
        "       PROCEDURE DIVISION.",
      ]);
      // Action bar, rule, title, command row, top marker, scale, bottom marker.
      expect(result.removed).toEqual({ chrome: 7, duplicates: 0 });
    });

    it("takes the window from a scale that opens on a multiple of five", () => {
      const pasted = editScreen("Columns 00005 00076", [
        " =COLS> +----1----+----2----+----3----+----4----+----5----+----6----+----7----+-",
        " 000100    IDENTIFICATION DIVISION.",
      ]).join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.firstColumn).toBe(5);
      expect(result.lines).toEqual(["       IDENTIFICATION DIVISION."]);
    });

    it("keeps a line two screens share once", () => {
      const first = editScreen("Columns 00001 00072", [
        TOP,
        " 000100        IDENTIFICATION DIVISION.",
        " 000200        PROGRAM-ID. PGM001.",
        " 000300        PROCEDURE DIVISION.",
      ]);
      const second = editScreen("Columns 00001 00072", [
        " 000200        PROGRAM-ID. PGM001.",
        " 000300        PROCEDURE DIVISION.",
        " 000400            GOBACK.",
      ]);
      const third = editScreen("Columns 00001 00072", [" 000400            GOBACK.", BOTTOM]);

      const result = normalizeScreenPaste([...first, ...second, ...third].join("\n"));

      expect(result.lines).toEqual([
        "       IDENTIFICATION DIVISION.",
        "       PROGRAM-ID. PGM001.",
        "       PROCEDURE DIVISION.",
        "           GOBACK.",
      ]);
      expect(result.removed.duplicates).toBe(3);
      expect(result.conflicts).toBe(0);
    });

    it("takes the later text where a shared line changed between the copies", () => {
      const first = editScreen("Columns 00001 00072", [
        " 000100        MOVE A TO B.",
        " 000200        MOVE C TO D.",
      ]);
      const second = editScreen("Columns 00001 00072", [
        " 000200        MOVE C TO E.",
        " 000300        GOBACK.",
      ]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["       MOVE A TO B.", "       MOVE C TO E.", "       GOBACK."]);
      expect(result.conflicts).toBe(1);
    });

    it("puts the COBOL sequence number back where the window starts at column 7", () => {
      const pasted = editScreen("Columns 00007 00072", [
        " 000100  IDENTIFICATION DIVISION.",
        " 000200 * A COMMENT",
        " 000300      MOVE A TO B.",
      ]).join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.firstColumn).toBe(7);
      expect(result.lines).toEqual([
        "000100 IDENTIFICATION DIVISION.",
        "000200* A COMMENT",
        "000300     MOVE A TO B.",
      ]);
    });

    it("keeps flagged lines and lines with a pending command, and reports excluded ones", () => {
      const pasted = editScreen("Columns 00001 00072", [
        " 000100        MOVE A TO B.",
        " ==CHG>        MOVE C TO D.",
        " D00300        MOVE E TO F.",
        " ''''''",
        " =NOTE= A NOTE LINE",
        " ==MSG> -Warning- The UNDO command is not available",
        " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  4 Line(s) not Displayed",
        " 000800        GOBACK.",
      ]).join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.lines).toEqual([
        "       MOVE A TO B.",
        "       MOVE C TO D.",
        "       MOVE E TO F.",
        "       GOBACK.",
      ]);
      expect(result.unnumbered).toBe(2);
      expect(result.hidden).toBe(4);
    });

    it("counts a full-width character as two cells and keeps the cells around it", () => {
      // The second row holds 64 cells, then four kanji on columns 65 to 72, then text past the window.
      const filler = `${" ".repeat(7)}${"X".repeat(57)}`;
      const pasted = editScreen("Columns 00001 00072", [
        " 000100            MOVE ' 売上集計 ' TO W-TITLE.",
        ` 000200 ${filler}売上集計TAIL`,
      ]).join("\n");

      expect(normalizeScreenPaste(pasted).lines).toEqual([
        "           MOVE ' 売上集計 ' TO W-TITLE.",
        `${filler}売上集計`,
      ]);
    });

    it("cuts the window at the last column the title names", () => {
      const window = `${" ".repeat(7)}${"X".repeat(65)}`;
      const pasted = editScreen("Columns 00001 00072", [` 000100 ${window}00010000`]).join("\n");

      expect(normalizeScreenPaste(pasted).lines).toEqual([window]);
    });
  });

  describe("an edit rectangle copied without its title", () => {
    const records = ["000100        MOVE A TO B.", "000200       * A COMMENT", "000300        GOBACK."];

    it("is left to the caller unless the kind is forced", () => {
      expect(normalizeScreenPaste(records.join("\n")).kind).toBe("plain");
    });

    it("reads fixed-format records as a window from column 1", () => {
      const result = normalizeScreenPaste(records.join("\n"), "ispf-edit");

      expect(result.firstColumn).toBe(1);
      expect(result.lines).toEqual(["       MOVE A TO B.", "      * A COMMENT", "       GOBACK."]);
    });

    it("reads JCL as a window from column 1", () => {
      const result = normalizeScreenPaste("000100 //JOB1 JOB (ACCT)\n000200 //STEP1 EXEC PGM=PGM001", "ispf-edit");

      expect(result.lines).toEqual(["//JOB1 JOB (ACCT)", "//STEP1 EXEC PGM=PGM001"]);
    });

    it("reads anything else as a window from column 7", () => {
      const result = normalizeScreenPaste("000100  MAIN-PROC.\n000200      MOVE A TO B.", "ispf-edit");

      expect(result.firstColumn).toBe(7);
      expect(result.lines).toEqual(["000100 MAIN-PROC.", "000200     MOVE A TO B."]);
    });
  });

  describe("an ISPF browse panel", () => {
    function browseScreen(line: string, rows: readonly string[]): string[] {
      return [
        "   Menu  Utilities  Compilers  Help",
        " -------------------------------------------------------------------------------",
        ` BROWSE    USER01.SRC.COBOL(PGM001)                 Line ${line} Col 001 080`,
        " Command ===>                                                  Scroll ===> CSR",
        ...rows,
      ];
    }

    it("keeps the data rows as they are", () => {
      const pasted = browseScreen("0000000000", [
        "********************************* Top of Data **********************************",
        "000100 IDENTIFICATION DIVISION.                                         00010000",
        "",
        "000300 PROCEDURE DIVISION.",
        "******************************** Bottom of Data ********************************",
      ]).join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.kind).toBe("ispf-browse");
      expect(result.lines).toEqual([
        "000100 IDENTIFICATION DIVISION.                                         00010000",
        "",
        "000300 PROCEDURE DIVISION.",
      ]);
    });

    it("drops the column-identification line and counts the data from the row under it", () => {
      const first = browseScreen("00000000", [
        "********************************* Top of Data **********************************",
        "LINE 1",
        "LINE 2",
      ]);
      const second = browseScreen("00000002", [
        "----+----1----+----2----+----3----+----4----+----5----+----6----+----7----+----8",
        "LINE 2",
        "LINE 3",
      ]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["LINE 1", "LINE 2", "LINE 3"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("knows the column-identification line of a window that starts on a multiple of ten", () => {
      const pasted = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 Line 00000000 Col 010 089",
        " Command ===>                                                  Scroll ===> CSR",
        "1----+----2----+----3----+----4----+----5----+----6----+----7----+----8----+----",
        "LINE 1",
      ].join("\n");

      expect(normalizeScreenPaste(pasted).lines).toEqual(["         LINE 1"]);
    });

    it("finds the lines two screens share from the line number in the title", () => {
      const first = browseScreen("0000000000", [
        "********************************* Top of Data **********************************",
        "LINE 1",
        "LINE 2",
        "LINE 3",
      ]);
      const second = browseScreen("0000000002", [
        "LINE 2",
        "LINE 3",
        "LINE 4",
        "******************************** Bottom of Data ********************************",
      ]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["LINE 1", "LINE 2", "LINE 3", "LINE 4"]);
      expect(result.removed.duplicates).toBe(2);
    });

    it("keeps equal lines that sit at different line numbers", () => {
      const first = browseScreen("0000000000", [
        "********************************* Top of Data **********************************",
        "           MOVE SPACE TO W-A.",
      ]);
      const second = browseScreen("0000000002", ["           MOVE SPACE TO W-A."]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toHaveLength(2);
      expect(result.removed.duplicates).toBe(0);
    });

    it("reads a screen whose title holds a message by the window of the titled screen, joined by text", () => {
      const titled = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 Line 00000000 Col 010 089",
        " Command ===>                                                  Scroll ===> CSR",
        "********************************* Top of Data **********************************",
        "LINE 1",
        "LINE 2",
      ];
      const found = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 CHARS 'LINE 3' FOUND",
        " Command ===>                                                  Scroll ===> CSR",
        "LINE 2",
        "LINE 3",
      ];

      const result = normalizeScreenPaste([...titled, ...found].join("\n"));

      expect(result.lines).toEqual(["         LINE 1", "         LINE 2", "         LINE 3"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("splits forced text where a Top of Data marker or a command row follows rows of data", () => {
      const text = [
        "LINE 1",
        "LINE 2",
        "********************************* Top of Data **********************************",
        "LINE 1",
        "LINE 2",
        "LINE 3",
        " Command ===>                                                  Scroll ===> CSR",
        "LINE 3",
        "LINE 4",
      ].join("\n");

      const result = normalizeScreenPaste(text, "ispf-browse");

      expect(result.lines).toEqual(["LINE 1", "LINE 2", "LINE 3", "LINE 4"]);
      expect(result.removed.duplicates).toBe(3);
    });

    it("joins untitled screens that each end on blank rows of the emulator", () => {
      const text = [
        "LINE 1",
        "LINE 2",
        "",
        "",
        " Command ===>                                                  Scroll ===> CSR",
        "LINE 2",
        "LINE 3",
        "",
      ].join("\n");

      const result = normalizeScreenPaste(text, "ispf-browse");

      expect(result.lines).toEqual(["LINE 1", "LINE 2", "LINE 3"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("keeps a blank row an untitled screen ends on where the next screen joins no line", () => {
      const text = ["A", "", " Command ===>                                                  Scroll ===> CSR", "B"].join("\n");

      expect(normalizeScreenPaste(text, "ispf-browse").lines).toEqual(["A", "", "B"]);
    });

    it("keeps a data row that starts with FMT", () => {
      expect(normalizeScreenPaste("LINE 1\nFMT CB DATA\nLINE 3", "ispf-browse").lines).toEqual([
        "LINE 1",
        "FMT CB DATA",
        "LINE 3",
      ]);
    });

    it("keeps data rows shaped like a key legend, a scale or a rule, and drops the legend after the data", () => {
      const rule = "-".repeat(30);
      const one = browseScreen("0000000000", [
        "********************************* Top of Data **********************************",
        "F1=ABC",
        "  1-2-3-4-5",
        rule,
      ]);
      // The next screen was copied from its title row down, so no action bar stands above the rule.
      const two = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 Line 0000000004 Col 001 080",
        " Command ===>                                                  Scroll ===> CSR",
        "LINE 4",
        " F1=Help      F2=Split     F3=Exit      F5=Rfind     F7=Up        F8=Down",
      ];

      const result = normalizeScreenPaste([...one, ...two].join("\n"));

      expect(result.lines).toEqual(["F1=ABC", "  1-2-3-4-5", rule, "LINE 4"]);
      // Action bar, rule, title, command row and marker of the first; title, command row and legend of the second.
      expect(result.removed.chrome).toBe(8);
    });
  });

  describe("an SEU display", () => {
    const head = [
      " Columns . . . :    1  71            Edit                     TESTLIB/QCBLLESRC",
      " SEU==>                                                                 ADDATOB",
      " FMT CB  ......-A+++B+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++",
    ];
    const keys = [
      " F3=Exit   F4=Prompt   F5=Refresh   F9=Retrieve   F10=Cursor   F11=Toggle",
      " F16=Repeat find       F17=Repeat change          F24=More keys",
    ];

    it("drops the panel rows and the sequence field", () => {
      const pasted = [
        ...head,
        "         *************** Beginning of data *************************************",
        " 0001.00        IDENTIFICATION DIVISION.",
        " 0002.00        PROGRAM-ID. ADDATOB.",
        " 0003.00       * A COMMENT",
        "         ****************** End of data ****************************************",
        ...keys,
      ].join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.kind).toBe("seu");
      expect(result.lines).toEqual([
        "       IDENTIFICATION DIVISION.",
        "       PROGRAM-ID. ADDATOB.",
        "      * A COMMENT",
      ]);
    });

    it("drops the date column to the right of the window", () => {
      const window = `${" ".repeat(7)}${"X".repeat(64)}`;
      const pasted = [...head, ` 0001.00 ${window} 260101`].join("\n");

      expect(normalizeScreenPaste(pasted).lines).toEqual([window]);
    });

    it("is recognised by its rows where the title was left out of the copy", () => {
      const first = ["0001.00        MOVE A TO B.", "0002.00        MOVE C TO D."];
      const result = normalizeScreenPaste(first.join("\n"));

      expect(result.kind).toBe("seu");
      expect(result.lines).toEqual(["       MOVE A TO B.", "       MOVE C TO D."]);
    });

    it("keeps a line two screens share once", () => {
      const pasted = [
        ...head,
        " 0001.00        MOVE A TO B.",
        " 0002.00        MOVE C TO D.",
        ...keys,
        ...head,
        " 0002.00        MOVE C TO D.",
        " 0003.00        GOBACK.",
        ...keys,
      ].join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.lines).toEqual(["       MOVE A TO B.", "       MOVE C TO D.", "       GOBACK."]);
      expect(result.removed.duplicates).toBe(1);
    });
  });

  describe("an SEU row under a pending line command", () => {
    const head = [
      " Columns . . . :    1  71            Edit                     TESTLIB/QCBLLESRC",
      " SEU==>                                                                 ADDATOB",
      " FMT CB  ......-A+++B+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++",
    ];

    it("is the line of the number it left, which a second screen shows alone and numbered, kept once", () => {
      // The D typed over the number leaves 002.00, which the rows around it complete to 0002.00.
      const pasted = [
        ...head,
        " 0001.00        MOVE A TO B.",
        " D002.00        MOVE C TO D.",
        " 0003.00        GOBACK.",
        ...head,
        " 0002.00        MOVE C TO D.",
      ].join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.kind).toBe("seu");
      expect(result.lines).toEqual(["       MOVE A TO B.", "       MOVE C TO D.", "       GOBACK."]);
      expect(result.removed.duplicates).toBe(1);
      expect(result.conflicts).toBe(0);
      expect(result.unnumbered).toBe(1);
    });

    it("is a data row where the rest of the field was erased, and takes the number its place gives it", () => {
      const pasted = [
        ...head,
        " 0001.00        MOVE A TO B.",
        ` ${"D".padEnd(7)}        MOVE C TO D.`,
        " 0003.00        GOBACK.",
        ...head,
        " 0002.00        MOVE C TO D.",
        " 0003.00        GOBACK.",
      ].join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.lines).toEqual(["       MOVE A TO B.", "       MOVE C TO D.", "       GOBACK."]);
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
      expect(result.unnumbered).toBe(1);
    });

    it("starts the next rectangle of untitled text where its number is the one above it", () => {
      const text = ["0001.00        A", "0002.00        B", "D002.00        B", "0003.00        C"].join("\n");

      const result = normalizeScreenPaste(text);

      expect(result.kind).toBe("seu");
      expect(result.lines.map((line) => line.trim())).toEqual(["A", "B", "C"]);
      expect(result.removed.duplicates).toBe(1);
    });
  });

  describe("text that is no screen", () => {
    it("is reported as plain, with no lines", () => {
      const result = normalizeScreenPaste("       IDENTIFICATION DIVISION.\n       PROGRAM-ID. PGM001.");

      expect(result.kind).toBe("plain");
      expect(result.lines).toEqual([]);
    });
  });

  describe("screens that open on a flagged row or abut each other", () => {
    const COLUMNS = "Columns 00001 00072";
    const row = (field: string, source: string): string => ` ${field}        ${source}`;
    const first = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("000300", "C")]);

    it("lays a changed row that opens a screen over the line it stands for", () => {
      const second = editScreen(COLUMNS, [row("==CHG>", "X"), row("000300", "C"), row("000400", "D")]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["       A", "       X", "       C", "       D"]);
      expect(result.removed.duplicates).toBe(1);
      expect(result.conflicts).toBe(1);
    });

    it("counts a flagged row that repeats its line as a duplicate", () => {
      const second = editScreen(COLUMNS, [row("==CHG>", "B"), row("000300", "C"), row("000400", "D")]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C", "       D"]);
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
    });

    it("keeps once the flagged rows one screen ends on and the next opens on", () => {
      const ending = editScreen(COLUMNS, [
        row("000100", "A"),
        row("000200", "B"),
        row("==CHG>", "C"),
        row("==CHG>", "D"),
      ]);
      const opening = editScreen(COLUMNS, [row("==CHG>", "C"), row("==CHG>", "D"), row("000500", "E")]);

      const result = normalizeScreenPaste([...ending, ...opening].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C", "       D", "       E"]);
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
    });

    it("keeps once a line the next screen shows with a line command typed over its number", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B")]);
      const opening = editScreen(COLUMNS, [row("I00200", "B"), row("000300", "C")]);

      const result = normalizeScreenPaste([...ending, ...opening].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("keeps once a flagged line the next screen shows under its number again", () => {
      const flagged = editScreen(COLUMNS, [row("000100", "A"), row("==CHG>", "B"), row("000300", "C")]);
      const reset = editScreen(COLUMNS, [row("000200", "B"), row("000300", "C"), row("000400", "D")]);

      const result = normalizeScreenPaste([...flagged, ...reset].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C", "       D"]);
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
    });

    it("keeps once a screen of flagged rows alone that repeats the end of the one before", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("==CHG>", "C"), row("==CHG>", "D")]);
      const opening = editScreen(COLUMNS, [row("==CHG>", "D"), row("==CHG>", "F")]);

      const result = normalizeScreenPaste([...ending, ...opening].join("\n"));

      expect(result.lines).toEqual(["       A", "       C", "       D", "       F"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("appends a screen that shares no line with the one before", () => {
      const second = editScreen(COLUMNS, [row("000400", "D"), row("000500", "E")]);

      const result = normalizeScreenPaste([...first, ...second].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C", "       D", "       E"]);
      expect(result.removed.duplicates).toBe(0);
      expect(result.conflicts).toBe(0);
    });

    it("keeps once a flagged row in the middle of the lines two screens share", () => {
      const ending = editScreen(COLUMNS, [
        row("000100", "A"),
        row("000200", "B"),
        row("==CHG>", "C"),
        row("000400", "D"),
      ]);
      const opening = editScreen(COLUMNS, [
        row("000200", "B"),
        row("==CHG>", "C"),
        row("000400", "D"),
        row("000500", "E"),
      ]);

      const result = normalizeScreenPaste([...ending, ...opening].join("\n"));

      expect(result.lines).toEqual(["       A", "       B", "       C", "       D", "       E"]);
      expect(result.removed.duplicates).toBe(3);
      expect(result.conflicts).toBe(0);
      // The flagged row is read once on each screen.
      expect(result.unnumbered).toBe(2);
    });

    it("reads rows that precede the first title row with the geometry of the titled screens", () => {
      // The titled screen's window starts at column 7, so the COBOL sequence number stands in the number
      // field and is the first thing the line holds. Rows 000100 and 000200 are copied without the
      // title; their windows are indented deep enough to pass for columns 1 to 72 of a fixed-format
      // record, which is the reading they get when they take no geometry from the titled screen.
      const beyond = `${" ".repeat(58)}YZ`;
      const untitled = [row("000100", "    MOVE A TO B."), row("000200", beyond)];
      const titled = editScreen("Columns 00007 00072", [row("000200", beyond), row("000300", "    GOBACK.")]);

      const result = normalizeScreenPaste([...untitled, ...titled].join("\n"));

      expect(result.kind).toBe("ispf-edit");
      expect(result.firstColumn).toBe(7);
      expect(result.lines).toEqual([
        `000100${" ".repeat(11)}MOVE A TO B.`,
        // The window is 66 cells wide, so the Z one cell past it is cut.
        `000200${" ".repeat(65)}Y`,
        `000300${" ".repeat(11)}GOBACK.`,
      ]);
      expect(result.removed.duplicates).toBe(1);
    });
  });

  describe("screens laid over each other by their line numbers", () => {
    const COLUMNS = "Columns 00001 00072";
    const row = (field: string, source: string): string => ` ${field}        ${source}`;
    const five = editScreen(COLUMNS, [
      row("000100", "A"),
      row("000200", "B"),
      row("000300", "C"),
      row("000400", "D"),
      row("000500", "E"),
    ]);
    const read = (...screens: string[][]): ReturnType<typeof normalizeScreenPaste> =>
      normalizeScreenPaste(screens.flat().join("\n"));
    const letters = (result: ReturnType<typeof normalizeScreenPaste>): string =>
      result.lines.map((line) => line.trim()).join(" ");

    it("keeps once a shared line the later screen shows under a pending line command", () => {
      const later = editScreen(COLUMNS, [
        row("000300", "C"),
        row("C00400", "D"),
        row("000500", "E"),
        row("000600", "F"),
      ]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C D E F");
      expect(result.removed.duplicates).toBe(3);
      expect(result.conflicts).toBe(0);
    });

    it("takes the later text of a shared line the later screen shows as changed", () => {
      const later = editScreen(COLUMNS, [row("000300", "C"), row("==CHG>", "X"), row("000500", "E")]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C X E");
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(1);
    });

    it("keeps once a flagged line that is the last one the two screens share", () => {
      const later = editScreen(COLUMNS, [row("000400", "D"), row("==ERR>", "E"), row("000600", "F")]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C D E F");
      expect(result.removed.duplicates).toBe(2);
    });

    it("adds a line the editor marks as newly inserted between the lines around it", () => {
      const later = editScreen(COLUMNS, [row("000200", "B"), row("''''''", "NEW"), row("000300", "C")]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B NEW C D E");
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
    });

    it("keeps the lines a later screen left out of its display", () => {
      const later = editScreen(COLUMNS, [
        row("000200", "B"),
        " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  2 Line(s) not Displayed",
        row("000500", "E"),
        row("000600", "F"),
      ]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C D E F");
      expect(result.removed.duplicates).toBe(2);
      // The earlier screen showed both, so the copy lacks none.
      expect(result.hidden).toBe(0);
    });

    it("lays a flagged row past lines left out of the display over the line it stands for", () => {
      const later = editScreen(COLUMNS, [
        row("000100", "A"),
        " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  2 Line(s) not Displayed",
        row("==CHG>", "X"),
        row("000500", "E"),
      ]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C X E");
      expect(result.conflicts).toBe(1);
    });

    it("keeps a numbered line that follows a flagged line of the same text", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("==CHG>", "END-IF.")]);
      const next = editScreen(COLUMNS, [row("000400", "END-IF."), row("000500", "Z")]);

      const result = read(ending, next);

      expect(letters(result)).toBe("A B END-IF. END-IF. Z");
      expect(result.removed.duplicates).toBe(0);
    });

    it("keeps a flagged line that follows a numbered line of the same text", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "END-IF.")]);
      const next = editScreen(COLUMNS, [row("==CHG>", "END-IF."), row("000400", "Z")]);

      const result = read(ending, next);

      expect(letters(result)).toBe("A END-IF. END-IF. Z");
      expect(result.removed.duplicates).toBe(0);
    });

    it("reads the number of a row under a command that carries a count", () => {
      const later = editScreen(COLUMNS, [row("000100", "A"), row("R50200", "B"), row("000300", "C")]);

      const result = read(five, later);

      expect(letters(result)).toBe("A B C D E");
      expect(result.removed.duplicates).toBe(3);
    });

    it("keeps a line that carries a label or a command with the rest of the field erased", () => {
      const screen = editScreen(COLUMNS, [
        row("000100", "A"),
        row(".HERE ", "B"),
        row("D     ", "C"),
        row("000400", "D"),
      ]);

      const result = read(screen);

      expect(letters(result)).toBe("A B C D");
      expect(result.unnumbered).toBe(2);
    });

    it("counts the lines left out of the display once where two screens show the same place", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  5 Line(s) not Displayed", row("000700", "G")]);
      const two = editScreen(COLUMNS, [row("000100", "A"), " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  5 Line(s) not Displayed", row("000700", "G"), row("000800", "H")]);

      const result = read(one, two);

      expect(letters(result)).toBe("A G H");
      expect(result.hidden).toBe(5);
    });

    it("puts a flagged row that opens a screen into the place an earlier screen left lines out of", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), " -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  5 Line(s) not Displayed", row("000700", "G")]);
      const two = editScreen(COLUMNS, [row("==CHG>", "F"), row("000700", "G"), row("000800", "H")]);

      const result = read(one, two);

      expect(letters(result)).toBe("A F G H");
      expect(result.conflicts).toBe(0);
      expect(result.hidden).toBe(4);
    });

    it("keeps once the lines of rectangles copied one after another with no title row", () => {
      const rectangles = ["000100 A", "000200 B", "000200 B", "000300 C"].join("\n");

      const result = normalizeScreenPaste(rectangles, "ispf-edit");

      // With no title row the window starts at the indicator, and the numbers fill the sequence area.
      expect(result.lines).toEqual(["000100A", "000200B", "000300C"]);
      expect(result.removed.duplicates).toBe(1);
    });

    it("reads each screen's number field where that screen has it", () => {
      const atEdge = editScreen(COLUMNS, ["000100        A", "000200        B"]);
      const oneIn = editScreen(COLUMNS, [row("000200", "B"), row("000300", "C"), row("000400", "D")]);

      const result = read(atEdge, oneIn);

      expect(letters(result)).toBe("A B C D");
      expect(result.removed.duplicates).toBe(1);
    });

    it("keeps the sequence number of a line a later screen shows under a flag", () => {
      // The window starts at the indicator, so the number field is the record's sequence area.
      const SEVEN = "Columns 00007 00072";
      const shown = (field: string, source: string): string => ` ${field}  ${source}`;
      const one = editScreen(SEVEN, [shown("000100", "A"), shown("000200", "B")]);
      const two = editScreen(SEVEN, [shown("000100", "A"), shown("==CHG>", "B"), shown("000300", "C")]);

      const result = read(one, two);

      expect(result.lines).toEqual(["000100 A", "000200 B", "000300 C"]);
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(0);
    });

    it("puts a screen copied out of turn where its numbers say", () => {
      const tail = editScreen(COLUMNS, [row("000400", "D"), row("000500", "E")]);
      const head = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("000300", "C")]);

      const result = read(tail, head);

      expect(letters(result)).toBe("A B C D E");
      expect(result.removed.duplicates).toBe(0);
    });

    it("keeps once every line of a screen that was scrolled back over lines already read", () => {
      const back = editScreen(COLUMNS, [row("000200", "B"), row("000300", "C")]);

      const result = read(five, back);

      expect(letters(result)).toBe("A B C D E");
      expect(result.removed.duplicates).toBe(2);
    });

    const excluded = (count: number): string =>
      ` -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  -  ${count} Line(s) not Displayed`;

    it("keeps once a line one screen ends on flagged and the next shows under its number after RESET", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("==CHG>", "C")]);
      const reset = editScreen(COLUMNS, [row("000300", "C"), row("000400", "D")]);

      const result = read(ending, reset);

      expect(letters(result)).toBe("A B C D");
      expect(result.removed.duplicates).toBe(1);
      expect(result.conflicts).toBe(0);
    });

    it("keeps once a labelled row, an error row and an erased command the other screen shows numbered", () => {
      const marked = editScreen(COLUMNS, [
        row("000100", "A"),
        row(".A    ", "B"),
        row("==ERR>", "C"),
        row("D     ", "D"),
        row("000500", "E"),
      ]);
      const numbered = editScreen(COLUMNS, [
        row("000200", "B"),
        row("000300", "C"),
        row("000400", "D"),
        row("000500", "E"),
        row("000600", "F"),
      ]);

      const result = read(marked, numbered);

      expect(letters(result)).toBe("A B C D E F");
      expect(result.removed.duplicates).toBe(4);
      expect(result.conflicts).toBe(0);
      expect(result.unnumbered).toBe(3);
    });

    it("keeps both of two adjacent lines of equal text flagged on either side of a screen boundary", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("==CHG>", "END-IF.")]);
      const opening = editScreen(COLUMNS, [row("==CHG>", "END-IF."), row("000500", "E")]);

      const result = read(ending, opening);

      expect(letters(result)).toBe("A B END-IF. END-IF. E");
      expect(result.removed.duplicates).toBe(0);
    });

    it("places a flagged row by the rows around it where the numbers rise unevenly", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), row("000110", "B"), row("000200", "C"), row("000210", "D")]);
      const two = editScreen(COLUMNS, [row("000110", "B"), row("==CHG>", "X"), row("000210", "D")]);

      const result = read(one, two);

      expect(letters(result)).toBe("A B X D");
      expect(result.removed.duplicates).toBe(2);
      expect(result.conflicts).toBe(1);
    });

    it("numbers a flagged row by its place in the sequence area of a window", () => {
      const shown = (field: string, source: string): string => ` ${field}  ${source}`;
      const screen = editScreen("Columns 00007 00072", [shown("000100", "A"), shown("000200", "B"), shown("==CHG>", "C")]);

      expect(read(screen).lines).toEqual(["000100 A", "000200 B", "000300 C"]);
    });

    it("reports excluded lines a screen opens or ends on that no other screen shows", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), excluded(2)]);
      const two = editScreen(COLUMNS, [excluded(1), row("000600", "F"), row("000700", "G")]);

      const result = read(one, two);

      expect(letters(result)).toBe("A B F G");
      // 000300 and 000400 after B, and 000500 ahead of F.
      expect(result.hidden).toBe(3);
    });

    it("counts once each line two overlapping markers stand for", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), excluded(3), row("000500", "E")]);
      const two = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), excluded(3), row("000600", "F")]);

      const result = read(one, two);

      expect(letters(result)).toBe("A B E F");
      // The markers stand for 000200 to 000400 and 000300 to 000500; B and E are shown.
      expect(result.hidden).toBe(2);
    });

    it("keeps a flagged, labelled or command row with an empty window as a blank line", () => {
      const screen = editScreen(COLUMNS, [row("000100", "A"), " ==CHG>", " .HERE", " D     ", " ''''''", row("000500", "E")]);

      const result = read(screen);

      // The inserted row with nothing in it is the editor's own.
      expect(result.lines).toEqual(["       A", "", "", "", "       E"]);
      expect(result.unnumbered).toBe(3);
    });

    it("keeps the columns only the first copy shows and takes the rest from a copy scrolled right", () => {
      const same = `${"       MOVE A TO B.".padEnd(40)}${"X".repeat(32)}TAIL`;
      const changed = `${"       MOVE C TO D.".padEnd(40)}YYYY`;
      const left = editScreen(COLUMNS, [` 000100 ${same}`, ` 000200 ${changed}`]);
      const right = editScreen("Columns 00041 00112", [` 000100 ${same.slice(40)}`, " 000200 ZZZZ"]);

      const result = read(left, right);

      expect(result.lines).toEqual([same, `${changed.slice(0, 40)}ZZZZ`]);
      expect(result.removed.duplicates).toBe(1);
      expect(result.conflicts).toBe(1);
    });

    it("appends a browse screen after edit lines, whose numbers count something else", () => {
      const browse = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 Line 00000100 Col 001 080",
        " Command ===>                                                  Scroll ===> CSR",
        "X",
        "Y",
      ];

      const result = read(editScreen(COLUMNS, [row("000100", "A"), row("000200", "B")]), browse);

      expect(letters(result)).toBe("A B X Y");
      expect(result.removed.duplicates).toBe(0);
    });

    it("reads a title that gives the columns with three digits", () => {
      const result = read(editScreen("COLUMNS 001 072", [row("000100", "A")]));

      expect(result.kind).toBe("ispf-edit");
      expect(result.lines).toEqual(["       A"]);
    });

    it("leaves the sequence area blank where the window from the indicator ends past column 72", () => {
      const result = read(editScreen("Columns 00007 00078", [" 000100  A"]));

      expect(result.lines).toEqual(["       A"]);
    });

    it("keeps once a flagged line two screens show at their edges where the numbering names another line", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("==CHG>", "X")]);
      const opening = editScreen(COLUMNS, [row("==CHG>", "X"), row("000300", "C"), row("000400", "D")]);

      const result = read(ending, opening);

      expect(letters(result)).toBe("A B X C D");
      expect(result.removed.duplicates).toBe(1);
      expect(result.conflicts).toBe(0);
    });

    it("gives the number to a numbered row where a flagged line at a screen's end guessed it with other text", () => {
      const ending = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("==CHG>", "X")]);
      const next = editScreen(COLUMNS, [row("000300", "C"), row("000400", "D")]);

      const result = read(ending, next);

      expect(letters(result)).toBe("A B X C D");
      expect(result.conflicts).toBe(0);
    });

    it("lets a labelled, error or command row repeat a numbered line by text where the numbers rise unevenly", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), row("000110", "B"), row("000200", "C")]);
      for (const field of [".A    ", "==ERR>", "D     "]) {
        const two = editScreen(COLUMNS, [row(field, "C"), row("000210", "D")]);

        const result = read(one, two);

        expect(letters(result)).toBe("A B C D");
        expect(result.removed.duplicates).toBe(1);
      }
    });

    it("keeps a changed row apart from a numbered line of the same text where the numbers rise unevenly", () => {
      const one = editScreen(COLUMNS, [row("000100", "A"), row("000110", "B"), row("000200", "C")]);
      const two = editScreen(COLUMNS, [row("==CHG>", "C"), row("000210", "D")]);

      expect(letters(read(one, two))).toBe("A B C C D");
    });

    it("keeps once a line inserted at the end of one screen that the next shows numbered after Enter", () => {
      const typed = editScreen(COLUMNS, [row("000100", "A"), row("000200", "B"), row("''''''", "NEW")]);
      const entered = editScreen(COLUMNS, [row("000210", "NEW"), row("000300", "C")]);

      const result = read(typed, entered);

      expect(letters(result)).toBe("A B NEW C");
      expect(result.removed.duplicates).toBe(1);
    });

    it("keeps a row whose flag has a line command or a label typed over its head", () => {
      const screen = editScreen(COLUMNS, [row("000100", "A"), row("D=CHG>", "B"), row(".ACHG>", "C"), row("000400", "D")]);

      const result = read(screen);

      expect(letters(result)).toBe("A B C D");
      expect(result.unnumbered).toBe(2);
    });

    it("reads the number field of a screen with no numbered row where its flags stand", () => {
      const atEdge = editScreen(COLUMNS, ["000100        A", "000200        B"]);
      const flagsOneIn = editScreen(COLUMNS, [row("==CHG>", "C"), row("==CHG>", "D")]);

      const result = read(atEdge, flagsOneIn);

      expect(letters(result)).toBe("A B C D");
      expect(result.unnumbered).toBe(2);
    });

    it("keeps a source line that names lines not displayed", () => {
      const screen = editScreen(COLUMNS, [row("000100", "DISPLAY '3 Line(s) not Displayed'."), row("000200", "B")]);

      const result = read(screen);

      expect(letters(result)).toBe("DISPLAY '3 Line(s) not Displayed'. B");
      expect(result.hidden).toBe(0);
    });

    it("counts lines excluded at a screen's start as shown by another screen", () => {
      const shown = editScreen(COLUMNS, [row("000100", "A"), row("000150", "B"), row("000200", "C")]);
      const opening = editScreen(COLUMNS, [excluded(2), row("000200", "C"), row("000300", "D")]);

      const result = read(shown, opening);

      expect(letters(result)).toBe("A B C D");
      expect(result.hidden).toBe(0);
    });

    it("keeps the lines of each panel kind together", () => {
      const browse = (line: string, rows: readonly string[]): string[] => [
        ` BROWSE    USER01.SRC.COBOL(PGM001)                 Line ${line} Col 001 080`,
        " Command ===>                                                  Scroll ===> CSR",
        ...rows,
      ];

      const result = read(
        browse("00000150", ["X", "Y"]),
        editScreen(COLUMNS, [row("000100", "A"), row("000200", "B")]),
        browse("00000152", ["Z"]),
      );

      expect(letters(result)).toBe("X Y Z A B");
    });
  });

  describe("a kind forced on text with no title row", () => {
    it("reads rows as a browse panel from column 1", () => {
      const result = normalizeScreenPaste("LINE 1\nLINE 2", "ispf-browse");

      expect(result.kind).toBe("ispf-browse");
      expect(result.firstColumn).toBe(1);
      expect(result.lines).toEqual(["LINE 1", "LINE 2"]);
    });

    it("reads numbered rows as an SEU display where the shape alone would not say so", () => {
      const text = [" 0001.00        MOVE A TO B.", "some row of the terminal", "another one"].join("\n");

      expect(normalizeScreenPaste(text).kind).toBe("plain");
      const result = normalizeScreenPaste(text, "seu");
      expect(result.kind).toBe("seu");
      expect(result.lines).toEqual(["       MOVE A TO B."]);
      expect(result.removed.chrome).toBe(2);
    });
  });

  describe("what lies beyond the data of a screen", () => {
    it("leaves out the rows after the bottom marker of a browse screen", () => {
      const pasted = [
        " BROWSE    USER01.SRC.COBOL(PGM001)                 Line 0000000000 Col 001 080",
        " Command ===>                                                  Scroll ===> CSR",
        "********************************* Top of Data **********************************",
        "LINE 1",
        "******************************** Bottom of Data ********************************",
        "ROW OF THE NEXT SCREEN",
        "ANOTHER ROW",
      ].join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.kind).toBe("ispf-browse");
      expect(result.lines).toEqual(["LINE 1"]);
    });

    it("holds no lines for an empty paste", () => {
      const result = normalizeScreenPaste("");

      expect(result.kind).toBe("plain");
      expect(result.lines).toEqual([]);
    });
  });

  describe("a full-width character on the last column of the window", () => {
    it("is cut to the spaces it covers inside the window, which the end of the line then drops", () => {
      // The title names window columns 1 to 72. The window holds 71 cells and then a kanji on 72-73.
      // Nothing can follow the cut kanji inside the window, so a kanji replaced by the space it covers
      // and a kanji dropped both end as the same text once the end of the line is trimmed.
      const kept = `${" ".repeat(7)}${"X".repeat(64)}`;
      const pasted = editScreen("Columns 00001 00072", [` 000100 ${kept}売`]).join("\n");

      const result = normalizeScreenPaste(pasted);

      expect(result.lines).toEqual([kept]);
    });

    it("keeps one that ends exactly on the last column", () => {
      // 70 cells, then a kanji on columns 71 and 72, then text past the window.
      const kept = `${" ".repeat(7)}${"X".repeat(63)}売`;
      const pasted = editScreen("Columns 00001 00072", [` 000100 ${kept}TAIL`]).join("\n");

      expect(normalizeScreenPaste(pasted).lines).toEqual([kept]);
    });
  });
});
