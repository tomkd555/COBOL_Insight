import { useState, type ReactElement } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, within } from "@testing-library/react";
import { fireEvent } from "@testing-library/dom";
import type { FlowEdge, FlowParagraph, ProgramFlow } from "../../../../shared/ipc";
import { text } from "../../i18n/text";
import { scanCobol } from "../../model/cobolOutline";
import { TRACE_NODE_BUDGET } from "../../model/traceTree";
import type { ProgramFlowView } from "../../state/useProgramFlow";
import type { ParagraphChoice } from "../../state/workbenchStore";
import { ParagraphPane, regionHeights } from "./ParagraphPane";

// The drawing and the second editor need a canvas and a layout; what is under test is the pane.
// The drawing's stand-in keeps its props, so a test can select a node as a click on the canvas would.
let canvasProps: { onSelectNode: (id: string) => void } | null = null;
vi.mock("../graph/GraphCanvas", () => ({
  GraphCanvas: (props: { onSelectNode: (id: string) => void }) => {
    canvasProps = props;
    return <div data-testid="graph-canvas" />;
  },
}));
vi.mock("./ParagraphPeek", () => ({
  ParagraphPeek: () => <div data-testid="paragraph-peek" />,
}));
vi.mock("../../state/useTheme", () => ({ useResolvedTheme: () => "light" }));

let flowView: ProgramFlowView = { state: { status: "none" }, stale: null };
vi.mock("../../state/useProgramFlow", () => ({ useProgramFlow: () => flowView }));

const LINES = [
  "000100 PROCEDURE DIVISION.",
  "000200 MAIN-PROC.",
  "000300     PERFORM SUB-PROC.",
  "000400     STOP RUN.",
  "000500 SUB-PROC.",
  "000600     EXIT.",
];

const FLOW: ProgramFlow = {
  paragraphs: [
    { id: 1, name: "MAIN-PROC", kind: "PARAGRAPH", sectionName: null, startLine: 2, endLine: 4 },
    { id: 2, name: "SUB-PROC", kind: "PARAGRAPH", sectionName: null, startLine: 5, endLine: 6 },
  ],
  edges: [
    {
      from: 1,
      to: 2,
      toName: "SUB-PROC",
      kind: "PERFORM",
      thru: null,
      thruName: null,
      line: 3,
      seq: 1,
      toPath: null,
    },
  ],
};

function renderPane(
  lines: readonly string[] = LINES,
  choice: ParagraphChoice = { root: null, shown: null },
): { onChoose: ReturnType<typeof vi.fn>; onReveal: ReturnType<typeof vi.fn> } {
  const onChoose = vi.fn();
  const onReveal = vi.fn();
  render(
    <ParagraphPane
      tabId="source:cobol/A.cbl"
      path="cobol/A.cbl"
      outline={scanCobol(lines)}
      choice={choice}
      onChoose={onChoose}
      onReveal={onReveal}
      onOpenAsset={vi.fn()}
    />,
  );
  return { onChoose, onReveal };
}

/** What the pane asked its parent to choose. */
const chosen = vi.fn();

/** The pane under a parent that keeps the choice, as the source editor does. */
function ChoosingPane({ lines, initial }: { lines: readonly string[]; initial: ParagraphChoice }): ReactElement {
  const [choice, setChoice] = useState(initial);
  return (
    <ParagraphPane
      tabId="source:cobol/A.cbl"
      path="cobol/A.cbl"
      outline={scanCobol(lines)}
      choice={choice}
      onChoose={(next) => {
        chosen(next);
        setChoice((current) => ({
          root: next.root === undefined ? current.root : next.root,
          shown: next.shown === undefined ? current.shown : next.shown,
        }));
      }}
      onReveal={vi.fn()}
      onOpenAsset={vi.fn()}
    />
  );
}

/** The name of the paragraph the lower region shows. */
function shownName(): string {
  const region = screen.getByRole("region", { name: text.sourceView.peek });
  return region.querySelector(".ci-pane__name")?.textContent ?? "";
}

/** The paragraphs of a program as the engine records them, each with the line it starts on. */
function paragraphsAt(...entries: [name: string, startLine: number][]): FlowParagraph[] {
  return entries.map(([name, startLine], index) => ({
    id: index + 1,
    name,
    kind: "PARAGRAPH",
    sectionName: null,
    startLine,
    endLine: startLine + 1,
  }));
}

/** A PERFORM from one paragraph to another, both named by id. */
function perform(from: number, to: number, toName: string, line: number): FlowEdge {
  return { from, to, toName, kind: "PERFORM", thru: null, thruName: null, line, seq: 1, toPath: null };
}

/** The text of the rows of the trace tree, top to bottom. */
function traceRows(): string[] {
  return within(screen.getByTestId("paragraph-trace-tree"))
    .queryAllByRole("treeitem")
    .map((row) => row.textContent ?? "");
}

beforeEach(() => {
  flowView = { state: { status: "ready", flow: FLOW }, stale: null };
  chosen.mockClear();
});

describe("regionHeights", () => {
  it("shares the pane evenly until a boundary has been moved", () => {
    expect(regionHeights(602, null)).toEqual({ peek: 200, graph: 200, least: 72 });
  });

  it("returns the chosen heights where the pane has room for them", () => {
    expect(regionHeights(602, { peek: 150, graph: 250 })).toMatchObject({ peek: 150, graph: 250 });
  });

  it("gives the text the room of the drawing while there is no trace to draw", () => {
    // 600 for the regions: with the drawing's 250 the text can hold 278, and without it 528.
    expect(regionHeights(602, { peek: 400, graph: 250 })).toMatchObject({ peek: 278, graph: 250 });
    expect(regionHeights(602, { peek: 400, graph: 250 }, false)).toMatchObject({ peek: 400 });
  });

  it("leaves every region its least height in a pane too short for what was chosen", () => {
    // 300 high: 298 for the regions. The drawing keeps 154, the text its least, the list the rest.
    expect(regionHeights(300, { peek: 100, graph: 250 })).toEqual({ peek: 72, graph: 154, least: 72 });
  });

  it("shares a pane too short for three regions of the least height", () => {
    expect(regionHeights(152, { peek: 100, graph: 250 })).toEqual({ peek: 50, graph: 50, least: 50 });
    expect(regionHeights(0, null)).toEqual({ peek: 0, graph: 0, least: 0 });
  });
});

describe("ParagraphPane", () => {
  describe("the pane beside the code", () => {
    it("opens on the list of paragraphs, with one tab in the tab order", () => {
      renderPane();
      const paragraphs = screen.getByTestId("pane-tab-paragraphs");
      const trace = screen.getByTestId("pane-tab-trace");
      expect(paragraphs).toHaveAttribute("aria-selected", "true");
      expect(paragraphs).toHaveAttribute("tabindex", "0");
      expect(trace).toHaveAttribute("tabindex", "-1");
      expect(screen.getByTestId("outline-tree")).toHaveTextContent("MAIN-PROC");
    });

    it("moves between the two tabs with the arrow keys", () => {
      renderPane();
      fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowRight" });
      expect(screen.getByTestId("pane-tab-trace")).toHaveAttribute("aria-selected", "true");
      // The statement's own line: the PERFORM is written on line 3.
      expect(screen.getByTestId("paragraph-trace-tree")).toHaveTextContent(`SUB-PROC${text.graph.line(3)}`);
      fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
      expect(screen.getByTestId("pane-tab-paragraphs")).toHaveAttribute("aria-selected", "true");
    });

    it("moves the focus to the tab the arrow key selected", () => {
      renderPane();
      screen.getByTestId("pane-tab-paragraphs").focus();

      fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowRight" });
      expect(screen.getByTestId("pane-tab-trace")).toHaveFocus();

      fireEvent.keyDown(screen.getByRole("tablist"), { key: "ArrowLeft" });
      expect(screen.getByTestId("pane-tab-paragraphs")).toHaveFocus();
    });

    it("makes a pressed paragraph both the one shown and the start of the trace", () => {
      const { onChoose } = renderPane();
      fireEvent.click(screen.getByTestId("outline-SUB-PROC"));
      expect(onChoose).toHaveBeenCalledWith({ root: "SUB-PROC", shown: "SUB-PROC" });
    });

    it("says so where the text holds no paragraph", () => {
      renderPane(["000100 01  WK-AREA PIC X."]);
      expect(screen.getByText(text.sourceView.noParagraphs)).toBeInTheDocument();
    });

    it("says where the trace stands against what was saved", () => {
      flowView = { state: { status: "ready", flow: FLOW }, stale: "reading" };
      renderPane();
      // The note stands over both views: the drawing under the list comes from the last scan too.
      expect(screen.getByRole("status")).toHaveTextContent(text.sourceView.flowStale);
      fireEvent.click(screen.getByTestId("pane-tab-trace"));
      expect(screen.getByRole("status")).toHaveTextContent(text.sourceView.flowStale);
    });

    it("says the save is unread once the scan after it failed or was stopped", () => {
      flowView = { state: { status: "ready", flow: FLOW }, stale: "unread" };
      renderPane();
      expect(screen.getByRole("status")).toHaveTextContent(text.sourceView.flowUnread);
      fireEvent.click(screen.getByTestId("pane-tab-trace"));
      expect(screen.getByRole("status")).toHaveTextContent(text.sourceView.flowUnread);
    });

    it("names the analysis as what is missing before any scan", () => {
      flowView = { state: { status: "none" }, stale: null };
      renderPane();
      fireEvent.click(screen.getByTestId("pane-tab-trace"));
      expect(screen.getByText(text.empty.notAnalysed)).toBeInTheDocument();
    });
  });

  describe("choosing in the list of paragraphs", () => {
    const LINES_WITH_SECTION = [
      "000100 DATA DIVISION.",
      "000200 WORKING-STORAGE SECTION.",
      "000300 01  WK-AREA PIC X.",
      "000400 PROCEDURE DIVISION.",
      "000500 MAIN-SECT SECTION.",
      "000600 MAIN-PROC.",
      "000700     EXIT.",
    ];

    it("starts the trace at a paragraph of a section, by its key with the section in front", () => {
      const { onChoose } = renderPane(LINES_WITH_SECTION);
      fireEvent.click(screen.getByTestId("outline-MAIN-SECT/MAIN-PROC"));
      expect(onChoose).toHaveBeenCalledWith({ root: "MAIN-SECT/MAIN-PROC", shown: "MAIN-SECT/MAIN-PROC" });
    });

    it("shows a row outside the procedure division and leaves the start of the trace where it is", () => {
      const { onChoose } = renderPane(LINES_WITH_SECTION);
      fireEvent.click(screen.getByTestId("outline-DATA DIVISION"));
      expect(onChoose).toHaveBeenCalledWith({ shown: "DATA DIVISION" });
    });
  });

  describe("the trace tab", () => {
    const CHAIN_LINES = [
      "000100 PROCEDURE DIVISION.",
      "000200 MAIN-PROC.",
      "000300     PERFORM SUB-A.",
      "000400     STOP RUN.",
      "000500 SUB-A.",
      "000600     PERFORM SUB-B.",
      "000700 SUB-B.",
      "000800     PERFORM SUB-C.",
      "000900 SUB-C.",
      "001000     EXIT.",
    ];
    const CHAIN: ProgramFlow = {
      paragraphs: paragraphsAt(["MAIN-PROC", 2], ["SUB-A", 5], ["SUB-B", 7], ["SUB-C", 9]),
      edges: [perform(1, 2, "SUB-A", 3), perform(2, 3, "SUB-B", 6), perform(3, 4, "SUB-C", 8)],
    };

    function openTrace(): void {
      fireEvent.click(screen.getByTestId("pane-tab-trace"));
    }

    it("opens the rows above the graph node that was selected, and selects its row", () => {
      flowView = { state: { status: "ready", flow: CHAIN }, stale: null };
      const { onChoose } = renderPane(CHAIN_LINES);
      openTrace();
      // Only the first level is open: the deeper rows are folded away.
      expect(traceRows()).toHaveLength(2);

      // The paragraph SUB-C has the id 4 in the flow.
      act(() => canvasProps?.onSelectNode("4"));

      const rows = within(screen.getByTestId("paragraph-trace-tree")).getAllByRole("treeitem");
      expect(rows.map((row) => row.textContent)).toEqual([
        expect.stringContaining("MAIN-PROC"),
        expect.stringContaining("SUB-A"),
        expect.stringContaining("SUB-B"),
        expect.stringContaining("SUB-C"),
      ]);
      expect(rows.filter((row) => row.getAttribute("aria-selected") === "true")).toEqual([rows[3]]);
      expect(onChoose).toHaveBeenCalledWith({ shown: "SUB-C" });
    });

    it("says the flow could not be read when the read failed", () => {
      flowView = { state: { status: "error", message: "the project file is gone" }, stale: null };
      renderPane(CHAIN_LINES);
      openTrace();

      expect(screen.getByRole("alert")).toHaveTextContent(text.sourceView.flowError);
      expect(screen.getByRole("alert")).toHaveTextContent("the project file is gone");
    });

    it("says where the trace was cut at its budget", () => {
      // Every paragraph performs the two after it, so the tree doubles at every level.
      const names = Array.from({ length: 40 }, (_, index) => `P${index}`);
      const lines = ["000100 PROCEDURE DIVISION.", ...names.flatMap((name) => [`${name}.`, "    EXIT."])];
      const edges = names.flatMap((_, index) =>
        [index + 1, index + 2]
          .filter((to) => to < names.length)
          .map((to) => perform(index + 1, to + 1, `P${to}`, 1)),
      );
      const paragraphs = paragraphsAt(...names.map((name, index): [string, number] => [name, 2 + index * 2]));
      flowView = { state: { status: "ready", flow: { paragraphs, edges } }, stale: null };
      renderPane(lines);
      openTrace();

      expect(screen.getByText(text.sourceView.truncated(TRACE_NODE_BUDGET))).toBeInTheDocument();
    });

    it("says so when the paragraph chosen as the start is missing from the flow", () => {
      flowView = { state: { status: "ready", flow: CHAIN }, stale: null };
      renderPane([...CHAIN_LINES, "001100 NEW-PARA.", "001200     EXIT."], { root: "NEW-PARA", shown: null });
      openTrace();

      expect(screen.getByText(text.sourceView.rootMissing)).toBeInTheDocument();
      expect(screen.queryByTestId("paragraph-trace-tree")).toBeNull();
    });

    it("shows the paragraph of a pressed row in the lower region", () => {
      render(<ChoosingPane lines={LINES} initial={{ root: null, shown: null }} />);
      expect(shownName()).toBe("MAIN-PROC");
      openTrace();

      fireEvent.click(screen.getByTestId("paragraph-trace-start/0"));

      expect(chosen).toHaveBeenCalledWith({ shown: "SUB-PROC" });
      expect(shownName()).toBe("SUB-PROC");
    });

    it("leaves the lower region as it is when the paragraph of a pressed row has left the text", () => {
      // The last scan still holds GONE-PARA, which the text no longer has.
      const paragraphs = paragraphsAt(["MAIN-PROC", 2], ["GONE-PARA", 5]);
      const flow = { paragraphs, edges: [perform(1, 2, "GONE-PARA", 3)] };
      flowView = { state: { status: "ready", flow }, stale: null };
      render(
        <ChoosingPane
          lines={["000100 PROCEDURE DIVISION.", "000200 MAIN-PROC.", "000300     STOP RUN."]}
          initial={{ root: null, shown: null }}
        />,
      );
      openTrace();

      fireEvent.click(screen.getByTestId("paragraph-trace-start/0"));

      expect(chosen).not.toHaveBeenCalled();
      expect(shownName()).toBe("MAIN-PROC");
    });

    it("gives the trace of the default root when the chosen root has left the outline", () => {
      // The flow opens on a paragraph the text no longer has. The default root is MAIN-PROC, where the
      // text opens its procedures; a trace from the first paragraph of the flow would start at OLD-PARA.
      const paragraphs = paragraphsAt(["OLD-PARA", 1], ["MAIN-PROC", 2], ["SUB-PROC", 5]);
      const flow = { paragraphs, edges: [perform(2, 3, "SUB-PROC", 3)] };
      flowView = { state: { status: "ready", flow }, stale: null };
      renderPane(LINES, { root: "GONE-PARA", shown: null });
      openTrace();

      expect(screen.queryByText(text.sourceView.rootMissing)).toBeNull();
      expect(traceRows()).toHaveLength(2);
      expect(traceRows()[0]).toContain("MAIN-PROC");
    });

    it("starts the trace at the chosen root while its header is in the outline", () => {
      renderPane(LINES, { root: "SUB-PROC", shown: null });
      openTrace();

      expect(traceRows()).toHaveLength(1);
      expect(traceRows()[0]).toContain("SUB-PROC");
    });

    it("says the chosen paragraph is missing when the flow holds its name only under another section", () => {
      const lines = [
        "000100 PROCEDURE DIVISION.",
        "000200 S1 SECTION.",
        "000300 P1.",
        "000400     EXIT.",
        "000500 S3 SECTION.",
        "000600 P1.",
        "000700     EXIT.",
      ];
      const paragraphs: FlowParagraph[] = [
        { id: 1, name: "S1", kind: "SECTION", sectionName: null, startLine: 2, endLine: 4 },
        { id: 2, name: "P1", kind: "PARAGRAPH", sectionName: "S1", startLine: 3, endLine: 4 },
      ];
      flowView = { state: { status: "ready", flow: { paragraphs, edges: [] } }, stale: null };
      renderPane(lines, { root: "S3/P1", shown: null });
      openTrace();

      expect(screen.getByText(text.sourceView.rootMissing)).toBeInTheDocument();
      expect(screen.queryByTestId("paragraph-trace-tree")).toBeNull();
    });

    it("starts at the first paragraph of the flow at or after the statements ahead of any paragraph", () => {
      const lines = [
        "000100 DATA DIVISION.",
        "000200 01  WK-AREA PIC X.",
        "000300 PROCEDURE DIVISION.",
        "000400     DISPLAY 'HI'.",
        "000500 MAIN-PROC.",
        "000600     EXIT.",
      ];
      // The flow still holds a paragraph from before the division, which an earlier scan read.
      const paragraphs = paragraphsAt(["OLD-PARA", 1], ["MAIN-PROC", 5]);
      flowView = { state: { status: "ready", flow: { paragraphs, edges: [] } }, stale: null };
      renderPane(lines);
      openTrace();

      expect(screen.queryByText(text.sourceView.rootMissing)).toBeNull();
      expect(traceRows()).toHaveLength(1);
      expect(traceRows()[0]).toContain("MAIN-PROC");
    });
  });

  describe("the head of the text of a paragraph", () => {
    it("reveals the first line of the paragraph on show in the editor", () => {
      const { onReveal } = renderPane(LINES, { root: null, shown: "SUB-PROC" });

      fireEvent.click(screen.getByTestId("pane-reveal"));

      expect(onReveal).toHaveBeenCalledWith(5);
    });
  });
});
