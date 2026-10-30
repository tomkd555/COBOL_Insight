import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { TraceKind, TraceRow } from "../../model/traceTree";
import { TraceTree } from "./TraceTree";

/** One closed row at the first level, with nothing under it. */
function row(id: string, kind: TraceKind = "paragraph"): TraceRow {
  return {
    node: { id, kind, label: id, line: null, path: null, graphId: null, children: [], cyclic: false },
    depth: 0,
    hasChildren: false,
    expanded: false,
  };
}

const ROWS = [row("A"), row("B"), row("C"), row("D")];

function tree(rows: readonly TraceRow[], selectedId: string | null, selectionKey = 0) {
  return (
    <TraceTree
      rows={rows}
      selectedId={selectedId}
      selectionKey={selectionKey}
      onSelect={vi.fn()}
      onToggle={vi.fn()}
      onOpen={vi.fn()}
    />
  );
}

/** The rows the Tab key can enter the tree at. */
function tabStops(): HTMLElement[] {
  return screen.getAllByRole("treeitem").filter((each) => each.tabIndex === 0);
}

describe("TraceTree", () => {
  describe("the Tab stop of the tree", () => {
    it("is the first row until something is selected", () => {
      render(tree(ROWS, null));

      expect(tabStops()).toEqual([screen.getByTestId("trace-A")]);
    });

    it("is the selected row once a selection is made outside the tree", () => {
      const view = render(tree(ROWS, null));
      // The reader first moves the stop to another row.
      fireEvent.keyDown(screen.getByTestId("trace-A"), { key: "ArrowDown" });
      expect(tabStops()).toEqual([screen.getByTestId("trace-B")]);

      view.rerender(tree(ROWS, "C", 1));

      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);
    });

    it("goes back to the selected row when the same row is selected again outside the tree", () => {
      const view = render(tree(ROWS, "C", 1));
      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);
      fireEvent.keyDown(screen.getByTestId("trace-C"), { key: "Home" });
      expect(tabStops()).toEqual([screen.getByTestId("trace-A")]);

      view.rerender(tree(ROWS, "C", 2));

      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);
    });

    it("stays where the reader moved it while no new selection is made outside the tree", () => {
      const view = render(tree(ROWS, "C", 1));
      fireEvent.keyDown(screen.getByTestId("trace-C"), { key: "Home" });

      view.rerender(tree(ROWS, "C", 1));

      expect(tabStops()).toEqual([screen.getByTestId("trace-A")]);
    });

    it("is the selected row when the tree opens on a selection", () => {
      render(tree(ROWS, "D"));

      expect(tabStops()).toEqual([screen.getByTestId("trace-D")]);
    });

    it("stays on the same row when a row above it opens and closes", () => {
      const view = render(tree(ROWS, "A"));
      fireEvent.keyDown(screen.getByTestId("trace-A"), { key: "ArrowDown" });
      fireEvent.keyDown(screen.getByTestId("trace-B"), { key: "ArrowDown" });
      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);

      // A row above the stop opens, so the rows under it move down by one.
      view.rerender(tree([row("A"), row("A/0"), ...ROWS.slice(1)], "A"));
      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);

      view.rerender(tree(ROWS, "A"));
      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);
    });

    it("goes to the selected row when the row that held it is closed away", () => {
      const view = render(tree([row("A"), row("A/0"), ...ROWS.slice(1)], "C"));
      fireEvent.keyDown(screen.getByTestId("trace-C"), { key: "Home" });
      fireEvent.keyDown(screen.getByTestId("trace-A"), { key: "ArrowDown" });
      expect(tabStops()).toEqual([screen.getByTestId("trace-A/0")]);

      view.rerender(tree(ROWS, "C"));

      expect(tabStops()).toEqual([screen.getByTestId("trace-C")]);
    });
  });

  describe("bringing the selected row into view", () => {
    const scrollIntoView = vi.fn();

    beforeEach(() => {
      scrollIntoView.mockClear();
      Element.prototype.scrollIntoView = scrollIntoView;
    });

    afterEach(() => {
      delete (Element.prototype as { scrollIntoView?: unknown }).scrollIntoView;
    });

    it("scrolls to a selection made outside the tree, once", () => {
      const view = render(tree(ROWS, null));
      expect(scrollIntoView).not.toHaveBeenCalled();

      view.rerender(tree(ROWS, "C"));

      expect(scrollIntoView).toHaveBeenCalledTimes(1);
      expect(scrollIntoView).toHaveBeenCalledWith({ block: "nearest" });
    });

    it("leaves the scroll where the reader put it when another row is opened or closed", () => {
      const view = render(tree(ROWS, "C"));
      expect(scrollIntoView).toHaveBeenCalledTimes(1);

      // A row above the selected one opens, so the rows change while the selection stays.
      const opened = [row("A"), row("A/0"), ...ROWS.slice(1)];
      view.rerender(tree(opened, "C"));
      // And closes again.
      view.rerender(tree(ROWS, "C"));

      expect(scrollIntoView).toHaveBeenCalledTimes(1);
    });

    it("scrolls again to a different selection", () => {
      const view = render(tree(ROWS, "B"));
      view.rerender(tree(ROWS, "D"));

      expect(scrollIntoView).toHaveBeenCalledTimes(2);
    });
  });
});
