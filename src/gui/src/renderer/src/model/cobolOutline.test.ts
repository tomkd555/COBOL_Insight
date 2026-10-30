import { describe, expect, it } from "vitest";
import { resolveAt, scanCobol } from "./cobolOutline";

/** Lays the lines out in fixed format: `A:` starts in area A, `B:` in area B, `*:` is a comment. */
function source(...lines: string[]): string[] {
  return lines.map((line) => {
    const body = line.slice(2);
    switch (line.slice(0, 2)) {
      case "A:":
        return `       ${body}`;
      case "B:":
        return `           ${body}`;
      case "*:":
        return `      *${body}`;
      case "D:":
        return `      D    ${body}`;
      case "-:":
        return `      -    ${body}`;
      default:
        return line;
    }
  });
}

const PROGRAM = source(
  "A:IDENTIFICATION DIVISION.", // 1
  "A:PROGRAM-ID. SAMPLE.", // 2
  "A:DATA DIVISION.", // 3
  "A:WORKING-STORAGE SECTION.", // 4
  "A:01  W-AREA.", // 5
  "B:05  W-A  PIC X.", // 6
  "A:01  W-B  PIC X.", // 7
  "A:PROCEDURE DIVISION.", // 8
  "A:MAIN-PROC.", // 9
  "B:PERFORM 1000-INIT THRU 1000-EXIT.", // 10
  "B:IF W-A = 'X'", // 11
  "B:    PERFORM SUB-PROC", // 12
  "B:END-IF.", // 13
  "B:GOBACK.", // 14
  "", // 15
  "*: the banner of the next paragraph", // 16
  "*: second banner line", // 17
  "A:1000-INIT.", // 18
  "B:MOVE 'A' TO W-A.", // 19
  "A:1000-EXIT.", // 20
  "B:EXIT.", // 21
  "A:SUB-PROC.", // 22
  "B:GO TO MAIN-PROC.", // 23
);

function names(lines: readonly string[]): string[] {
  return scanCobol(lines).nodes.map((node) => `${"  ".repeat(node.depth)}${node.kind} ${node.name}`);
}

describe("scanCobol", () => {
  describe("the outline", () => {
    it("lists divisions, the sections of every division and the paragraphs of the procedure division", () => {
      expect(names(PROGRAM)).toEqual([
        "division IDENTIFICATION",
        "division DATA",
        "  section WORKING-STORAGE",
        "division PROCEDURE",
        "  paragraph MAIN-PROC",
        "  paragraph 1000-INIT",
        "  paragraph 1000-EXIT",
        "  paragraph SUB-PROC",
      ]);
    });

    it("ends a paragraph at its last statement, leaving the banner to the paragraph below", () => {
      const outline = scanCobol(PROGRAM);

      expect(outline.byKey.get("MAIN-PROC")).toMatchObject({ startLine: 9, endLine: 14 });
      expect(outline.byKey.get("1000-INIT")).toMatchObject({ startLine: 18, endLine: 19 });
      expect(outline.byKey.get("PROCEDURE DIVISION")).toMatchObject({ startLine: 8, endLine: 23 });
    });

    it("reads a header whose sentence goes on along the line", () => {
      const outline = scanCobol(source("A:PROCEDURE DIVISION.", "A:MAIN-PROC. PERFORM SUB-PROC.", "A:SUB-PROC.", "B:GOBACK."));

      expect(outline.nodes.map((node) => node.name)).toEqual(["PROCEDURE", "MAIN-PROC", "SUB-PROC"]);
      expect(outline.refs).toEqual([{ line: 2, startColumn: 27, endColumn: 35, key: "SUB-PROC" }]);
    });

    it("takes a name only from area A, and only one that is a user-defined word", () => {
      const outline = scanCobol(
        source("A:PROCEDURE DIVISION.", "A:MAIN-PROC.", "B:NOT-A-NAME.", "A:GOBACK.", "A:EXIT."),
      );

      expect(outline.nodes.map((node) => node.name)).toEqual(["PROCEDURE", "MAIN-PROC"]);
    });

    it("takes a name made of digits alone in the procedure division, and a level number nowhere", () => {
      const lines = source(
        "A:DATA DIVISION.", // 1
        "A:WORKING-STORAGE SECTION.", // 2
        "A:77  W-COUNT PIC 9.", // 3
        "A:PROCEDURE DIVISION.", // 4
        "A:1000 SECTION.", // 5
        "A:0100.", // 6
        "B:PERFORM 0200 THRU 0300.", // 7
        "B:PERFORM 5 TIMES", // 8
        "B:    MOVE 0200 TO W-COUNT", // 9
        "B:END-PERFORM.", // 10
        "B:GO TO 0300.", // 11
        "A:0200.", // 12
        "B:CONTINUE.", // 13
        "A:0300.", // 14
        "B:GOBACK.", // 15
      );
      const outline = scanCobol(lines);

      expect(names(lines)).toEqual([
        "division DATA",
        "  section WORKING-STORAGE",
        "division PROCEDURE",
        "  section 1000",
        "    paragraph 0100",
        "    paragraph 0200",
        "    paragraph 0300",
      ]);
      // The count of PERFORM ... TIMES and the literal moved on line 9 stay what they are.
      expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual([
        "7:1000/0200",
        "7:1000/0300",
        "11:1000/0300",
      ]);
    });

    it("passes over comment, debug and continuation lines and over the sequence numbers", () => {
      const outline = scanCobol([
        "000100 PROCEDURE DIVISION.                                              00010000",
        "000200 MAIN-PROC.                                                       SUB-PROC.",
        "000300*OLD-PROC.",
        "000400D    DEBUG-PROC.",
        "000500     DISPLAY 'A LITERAL THAT RUNS TO THE END OF THE LINE AND ON INTO",
        "000600-    ' THE NEXT. SUB-PROC.'.",
        "000700 SUB-PROC.",
      ]);

      expect(outline.nodes.map((node) => node.name)).toEqual(["PROCEDURE", "MAIN-PROC", "SUB-PROC"]);
      expect(outline.refs).toEqual([]);
    });

    it("places the paragraphs of a section under it, and the sections of declaratives under those", () => {
      expect(
        names(
          source(
            "A:PROCEDURE DIVISION.",
            "A:DECLARATIVES.",
            "A:ERR-SECT SECTION.",
            "B:USE AFTER ERROR PROCEDURE ON INPUT.",
            "A:ERR-PROC.",
            "B:DISPLAY 'E'.",
            "A:END DECLARATIVES.",
            "A:MAIN-SECT SECTION.",
            "A:MAIN-PROC.",
            "B:GOBACK.",
          ),
        ),
      ).toEqual([
        "division PROCEDURE",
        "  declaratives DECLARATIVES",
        "    section ERR-SECT",
        "      paragraph ERR-PROC",
        "  section MAIN-SECT",
        "    paragraph MAIN-PROC",
      ]);
    });

    it("closes everything at END PROGRAM", () => {
      const outline = scanCobol(
        source("A:PROCEDURE DIVISION.", "A:MAIN-PROC.", "B:GOBACK.", "A:END PROGRAM SAMPLE.", "", "*: trailer"),
      );

      expect(outline.byKey.get("MAIN-PROC")).toMatchObject({ endLine: 3 });
      expect(outline.byKey.get("PROCEDURE DIVISION")).toMatchObject({ endLine: 3 });
    });

    it("reads a member with no division header as procedure text", () => {
      expect(names(source("A:COMMON-PROC.", "B:MOVE 'A' TO W-A.", "A:COMMON-EXIT.", "B:EXIT."))).toEqual([
        "paragraph COMMON-PROC",
        "paragraph COMMON-EXIT",
      ]);
    });

    it("finds nothing in a data copybook", () => {
      const outline = scanCobol(source("A:01  W-AREA.", "B:05  W-A  PIC X.", "B:05  W-B  PIC X."));

      expect(outline.nodes).toEqual([]);
      expect(outline.defaultRoot).toBeNull();
    });
  });

  describe("where execution starts", () => {
    it("is the first paragraph of the procedure division", () => {
      expect(scanCobol(PROGRAM).defaultRoot).toBe("MAIN-PROC");
    });

    it("is the first section after the declaratives", () => {
      const outline = scanCobol(
        source(
          "A:PROCEDURE DIVISION.",
          "A:DECLARATIVES.",
          "A:ERR-SECT SECTION.",
          "A:END DECLARATIVES.",
          "A:MAIN-SECT SECTION.",
          "A:MAIN-PROC.",
        ),
      );

      expect(outline.defaultRoot).toBe("MAIN-SECT");
    });

    it("is the division itself where statements come ahead of the first paragraph", () => {
      const outline = scanCobol(source("A:PROCEDURE DIVISION USING", "B:W-PARM.", "B:PERFORM SUB-PROC.", "A:SUB-PROC."));

      expect(outline.defaultRoot).toBe("PROCEDURE DIVISION");
    });

    it("is not moved by a USING phrase that runs on to the next line", () => {
      const outline = scanCobol(source("A:PROCEDURE DIVISION USING", "B:W-PARM.", "A:MAIN-PROC.", "B:GOBACK."));

      expect(outline.defaultRoot).toBe("MAIN-PROC");
    });
  });

  describe("the folding ranges", () => {
    const folds = (lines: readonly string[]): string[] =>
      scanCobol(lines)
        .folds.map((fold) => `${fold.start}-${fold.end}${fold.comment ? " comment" : ""}`)
        .sort((left, right) => Number.parseInt(left, 10) - Number.parseInt(right, 10));

    it("cover the outline, the data entries, the statement blocks and the comment runs", () => {
      expect(folds(PROGRAM)).toEqual([
        "1-2", // identification division
        "3-7", // data division
        "4-7", // working-storage section
        "5-6", // 01 W-AREA
        "8-23", // procedure division
        "9-14", // MAIN-PROC
        "11-12", // IF, with END-IF left in view
        "16-17 comment",
        "18-19",
        "20-21",
        "22-23",
      ]);
    });

    it("nest, and close at the terminator of their own verb", () => {
      expect(
        folds(
          source(
            "A:MAIN-PROC.",
            "B:EVALUATE TRUE", // 2
            "B:    WHEN W-A = 'X'",
            "B:        PERFORM VARYING I FROM 1 BY 1 UNTIL I > 9", // 4
            "B:            READ IN-FILE", // 5
            "B:                AT END MOVE 'Y' TO W-EOF",
            "B:            END-READ",
            "B:        END-PERFORM",
            "B:        PERFORM SUB-PROC",
            "B:END-EVALUATE.",
          ),
        ),
      ).toEqual(["1-10", "2-9", "4-7", "5-6"]);
    });

    it("let go of every open block at a period", () => {
      expect(
        folds(source("A:MAIN-PROC.", "B:READ IN-FILE", "B:    AT END GO TO MAIN-EXIT.", "B:IF W-A = 'X'", "B:    GOBACK", "B:END-IF.")),
      ).toEqual(["1-6", "4-5"]);
    });

    it("take an EXEC block whole, whatever its text holds", () => {
      expect(
        folds(
          source(
            "A:MAIN-PROC.",
            "B:EXEC SQL",
            "B:    SELECT A INTO :W-A FROM T",
            "B:    WHERE B = 'IF' AND C IN (SELECT D FROM U)",
            "B:END-EXEC.",
          ),
        ),
      ).toEqual(["1-5", "2-4"]);
    });
  });
});

describe("the references", () => {
  it("are the places a section or paragraph is named, forward references included", () => {
    expect(scanCobol(PROGRAM).refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual([
      "10:1000-INIT",
      "10:1000-EXIT",
      "12:SUB-PROC",
      "23:MAIN-PROC",
    ]);
  });

  it("leave a data item alone that shares a paragraph's name", () => {
    const outline = scanCobol(
      source(
        "A:PROCEDURE DIVISION.",
        "A:MAIN-PROC.",
        "B:MOVE TOTAL TO WS-A.", // 3
        "B:GO TO TOTAL FINISH DEPENDING ON WS-A.", // 4
        "B:SORT WK ON ASCENDING KEY K INPUT PROCEDURE IS TOTAL", // 5
        "B:    OUTPUT PROCEDURE FINISH.", // 6
        "A:TOTAL.",
        "A:FINISH.",
      ),
    );

    expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual([
      "4:TOTAL",
      "4:FINISH",
      "5:TOTAL",
      "6:FINISH",
    ]);
  });

  it("follow a statement over a line break, and an ALTER with or without PROCEED", () => {
    const outline = scanCobol(
      source(
        "A:PROCEDURE DIVISION.",
        "A:MAIN-PROC.",
        "B:GO", // 3
        "B:    TO TOTAL.", // 4
        "B:ALTER MAIN-PROC TO TOTAL.", // 5
        "B:ALTER MAIN-PROC TO PROCEED TO FINISH.", // 6
        "A:TOTAL.",
        "A:FINISH.",
      ),
    );

    expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual([
      "4:TOTAL",
      "5:MAIN-PROC",
      "5:TOTAL",
      "6:MAIN-PROC",
      "6:FINISH",
    ]);
  });

  it("leave the data items of a range and of a count alone", () => {
    const outline = scanCobol(
      source(
        "A:PROCEDURE DIVISION.",
        "A:MAIN-PROC.",
        "B:EVALUATE WS-A WHEN TOTAL THRU FINISH CONTINUE END-EVALUATE.", // 3
        "B:PERFORM TOTAL TIMES CONTINUE END-PERFORM.", // 4
        "B:PERFORM TOTAL THRU FINISH.", // 5
        "A:TOTAL.",
        "A:FINISH.",
      ),
    );

    expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual(["5:TOTAL", "5:FINISH"]);
  });

  it("leave PERFORM n TIMES alone where a paragraph is named n, and take PERFORM n", () => {
    const outline = scanCobol(
      source(
        "A:PROCEDURE DIVISION.",
        "A:MAIN-PROC.",
        "B:PERFORM 5 TIMES CONTINUE END-PERFORM.", // 3
        "B:PERFORM 5.", // 4
        "A:5.",
        "B:EXIT.",
      ),
    );

    expect(outline.byKey.has("5")).toBe(true);
    expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual(["4:5"]);
  });

  it("resolve to the paragraph of the named section, then of the same section, then to the first", () => {
    const outline = scanCobol(
      source(
        "A:PROCEDURE DIVISION.",
        "A:S1 SECTION.",
        "A:P.",
        "B:PERFORM P.", // 4
        "B:PERFORM P OF S2.", // 5
        "A:S2 SECTION.",
        "A:P.",
        "B:PERFORM P.", // 8
        "B:PERFORM Q.", // 9
        "A:S3 SECTION.",
        "A:Q.",
      ),
    );

    expect(outline.refs.map((ref) => `${ref.line}:${ref.key}`)).toEqual([
      "4:S1/P",
      "5:S2/P",
      "5:S2",
      "8:S2/P",
      "9:S3/Q",
    ]);
  });

  it("are found from a position inside the name", () => {
    const outline = scanCobol(PROGRAM);
    const column = (PROGRAM[11] ?? "").indexOf("SUB-PROC") + 1;

    expect(resolveAt(outline, 12, column)).toBe("SUB-PROC");
    expect(resolveAt(outline, 12, column + 8)).toBe("SUB-PROC");
    expect(resolveAt(outline, 12, column - 2)).toBeNull();
    expect(resolveAt(outline, 14, 12)).toBeNull();
  });

  it("use the area offsets given for a line with double-byte characters", () => {
    // Three full-width characters in the sequence area: the indicator is the fourth character.
    const lines = ["       PROCEDURE DIVISION.", "００１ MAIN-PROC.", "           GOBACK."];
    const outline = scanCobol(lines, () => [3, 4, 8, -1]);

    expect(outline.nodes.map((node) => node.name)).toEqual(["PROCEDURE", "MAIN-PROC"]);
  });
});
