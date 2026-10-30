/**
 * The scan budget. The outline is read again for every version of the text the editor holds, on the
 * render thread, so a 20,000-line program has to be read well inside the time a keystroke takes to
 * reach the screen.
 */

import { describe, expect, it } from "vitest";
import { scanCobol } from "./cobolOutline";

const LINE_COUNT = 20000;
// 100 ms is the plan's figure for a developer machine; a shared CI runner gets three times that, as
// the layout budget does: the check is against a runaway.
const BUDGET_MS = process.env.CI === undefined ? 100 : 300;

/** A program of paragraphs of twenty lines each: PERFORMs, a nested block, a literal, a comment. */
function syntheticProgram(lineCount: number): string[] {
  const lines = ["       IDENTIFICATION DIVISION.", "       PROGRAM-ID. BIG.", "       PROCEDURE DIVISION."];
  for (let paragraph = 0; lines.length < lineCount; paragraph++) {
    lines.push(
      `      * paragraph ${paragraph}`,
      `       P${paragraph}-PROC.`,
      `           PERFORM P${paragraph + 1}-PROC THRU P${paragraph + 2}-PROC.`,
      "           IF W-A = 'X'",
      "               EVALUATE TRUE",
      "                   WHEN W-B = 1",
      "                       MOVE 'A LITERAL, WITH A PERIOD. INSIDE' TO W-C",
      "                   WHEN OTHER",
      "                       PERFORM VARYING I FROM 1 BY 1 UNTIL I > 9",
      "                           ADD 1 TO W-D",
      "                       END-PERFORM",
      "               END-EVALUATE",
      "           ELSE",
      "               READ IN-FILE",
      "                   AT END MOVE 'Y' TO W-EOF",
      "               END-READ",
      "           END-IF.",
      `           GO TO P${paragraph}-PROC.`,
      "           MOVE W-A TO W-B.",
      "           CONTINUE.",
    );
  }
  return lines.slice(0, lineCount);
}

describe("scanCobol", () => {
  it(`reads ${LINE_COUNT} lines within ${BUDGET_MS} ms`, () => {
    const lines = syntheticProgram(LINE_COUNT);
    scanCobol(lines.slice(0, 2000));

    const started = performance.now();
    const outline = scanCobol(lines);
    const elapsed = performance.now() - started;

    expect(outline.nodes.length).toBeGreaterThan(900);
    expect(elapsed).toBeLessThan(BUDGET_MS);
  });
});
