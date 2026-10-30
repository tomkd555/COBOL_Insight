import { describe, expect, it } from "vitest";
import {
  PANEL_LIMITS,
  SIDE_LIMITS,
  activeTabOf,
  draftOf,
  graphTab,
  initialWorkbenchState,
  isTabDirty,
  paragraphChoiceOf,
  sourceTab,
  sourceTabId,
  workbenchReducer,
  type WorkbenchAction,
  type WorkbenchState,
} from "./workbenchStore";

function apply(state: WorkbenchState, ...actions: WorkbenchAction[]): WorkbenchState {
  return actions.reduce(workbenchReducer, state);
}

const A = sourceTab("cobol/A.cbl");
const B = sourceTab("cobol/B.cbl");
const C = sourceTab("cobol/C.cbl");

describe("sourceTab", () => {
  it("titles the tab with the file name and keeps the path", () => {
    expect(A).toEqual({
      id: sourceTabId("cobol/A.cbl"),
      kind: "source",
      title: "A.cbl",
      path: "cobol/A.cbl",
      line: null,
    });
  });
});

describe("workbenchReducer", () => {
  describe("tabs", () => {
    it("opens a tab as the active tab", () => {
      const state = apply(initialWorkbenchState, { type: "OPEN_TAB", tab: A });
      expect(state.tabs).toHaveLength(1);
      expect(activeTabOf(state)).toEqual(A);
    });

    it("does not open the same asset twice, only updates the requested line", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "OPEN_TAB", tab: sourceTab("cobol/A.cbl", 42) },
      );
      expect(state.tabs).toHaveLength(2);
      expect(activeTabOf(state)?.line).toBe(42);
    });

    it("selects the following tab when the active one closes", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "OPEN_TAB", tab: C },
        { type: "ACTIVATE_TAB", id: B.id },
        { type: "CLOSE_TAB", id: B.id },
      );
      expect(state.activeTabId).toBe(C.id);
    });

    it("selects the preceding tab when the last one closes", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "CLOSE_TAB", id: B.id },
      );
      expect(state.activeTabId).toBe(A.id);
    });

    it("leaves the selection alone when an inactive tab closes", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "SELECT", selection: { path: "cobol", kind: "folder" } },
        { type: "CLOSE_TAB", id: A.id },
      );
      expect(state.activeTabId).toBe(B.id);
      expect(state.selection).toEqual({ path: "cobol", kind: "folder" });
    });

    it("selects nothing once the last tab is gone", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "CLOSE_TAB", id: A.id },
      );
      expect(state.activeTabId).toBeNull();
      expect(activeTabOf(state)).toBeNull();
    });

    it("closes every tab, its drafts included, when the asset folder changes", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "SET_DRAFT", id: A.id, draft: "EDITED" },
        { type: "CLOSE_ALL_TABS" },
      );
      expect(state.tabs).toEqual([]);
      expect(state.activeTabId).toBeNull();
      expect(state.drafts).toEqual({});
    });

    it("holds the explorer's selection", () => {
      const selected = apply(initialWorkbenchState, {
        type: "SELECT",
        selection: { path: "cobol", kind: "folder" },
      });
      expect(selected.selection).toEqual({ path: "cobol", kind: "folder" });
      // Selecting the same row again changes nothing, so what is keyed on the state stays put.
      expect(
        workbenchReducer(selected, { type: "SELECT", selection: { path: "cobol", kind: "folder" } }),
      ).toBe(selected);
    });

    it("drops the selection with the folder", () => {
      const selected = apply(initialWorkbenchState, {
        type: "SELECT",
        selection: { path: "cobol", kind: "folder" },
      });
      // That path names nothing in the folder being opened.
      expect(workbenchReducer(selected, { type: "CLOSE_ALL_TABS" }).selection).toBeNull();
    });

    it("moves the selection to the asset of the tab brought forward", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "SELECT", selection: { path: "cobol", kind: "folder" } },
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "ACTIVATE_TAB", id: A.id },
      );
      expect(state.selection).toEqual({ path: "cobol/A.cbl", kind: "file" });
    });

    it("keeps the selection when a tab without an asset opens", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "SELECT", selection: { path: "cobol", kind: "folder" } },
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "ACTIVATE_TAB", id: A.id },
      );
      const graph = apply(state, { type: "OPEN_TAB", tab: graphTab("呼び出し関係図") });
      expect(graph.selection).toEqual({ path: "cobol/A.cbl", kind: "file" });
    });

    it("wraps around at either end when stepping", () => {
      const open = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
      );
      expect(apply(open, { type: "STEP_TAB", step: 1 }).activeTabId).toBe(A.id);
      expect(apply(open, { type: "STEP_TAB", step: -1 }).activeTabId).toBe(A.id);
    });

    it("ignores a step and an activation when nothing matches", () => {
      expect(apply(initialWorkbenchState, { type: "STEP_TAB", step: 1 })).toBe(initialWorkbenchState);
      expect(apply(initialWorkbenchState, { type: "ACTIVATE_TAB", id: "nope" })).toBe(
        initialWorkbenchState,
      );
    });
  });

  describe("drafts", () => {
    it("marks a tab dirty once it holds edited text", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "SET_DRAFT", id: A.id, draft: "EDITED" },
      );
      expect(isTabDirty(state, A.id)).toBe(true);
      expect(draftOf(state, A.id)).toBe("EDITED");
    });

    it("clears the dirty mark when the draft is dropped", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "SET_DRAFT", id: A.id, draft: "EDITED" },
        { type: "SET_DRAFT", id: A.id, draft: null },
      );
      expect(isTabDirty(state, A.id)).toBe(false);
    });

    it("discards the draft along with the tab", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "SET_DRAFT", id: A.id, draft: "EDITED" },
        { type: "CLOSE_TAB", id: A.id },
      );
      expect(state.drafts).toEqual({});
    });

    it("returns the same state when the draft has not changed", () => {
      const edited = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "SET_DRAFT", id: A.id, draft: "EDITED" },
      );
      expect(apply(edited, { type: "SET_DRAFT", id: A.id, draft: "EDITED" })).toBe(edited);
    });
  });

  describe("the paragraph pane", () => {
    it("keeps the trace start and the shown paragraph per tab, each set on its own", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "SET_PARAGRAPH", id: A.id, root: "MAIN-PROC", shown: "MAIN-PROC" },
        { type: "SET_PARAGRAPH", id: A.id, shown: "S1/SUB-PROC" },
      );
      expect(paragraphChoiceOf(state, A.id)).toEqual({ root: "MAIN-PROC", shown: "S1/SUB-PROC" });
      expect(paragraphChoiceOf(state, B.id)).toEqual({ root: null, shown: null });
      // Setting what is already set leaves the state as it is, so nothing re-renders.
      expect(workbenchReducer(state, { type: "SET_PARAGRAPH", id: A.id, shown: "S1/SUB-PROC" })).toBe(state);
    });

    it("drops the choice with its tab", () => {
      const chosen = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "SET_PARAGRAPH", id: A.id, root: "MAIN-PROC" },
        { type: "SET_PARAGRAPH", id: B.id, root: "OTHER-PROC" },
      );
      const closed = workbenchReducer(chosen, { type: "CLOSE_TAB", id: A.id });
      expect(closed.paragraphs).toEqual({ [B.id]: { root: "OTHER-PROC", shown: null } });
    });

    it("drops every choice with the folder", () => {
      const chosen = apply(
        initialWorkbenchState,
        { type: "OPEN_TAB", tab: A },
        { type: "OPEN_TAB", tab: B },
        { type: "SET_PARAGRAPH", id: A.id, root: "MAIN-PROC" },
        { type: "SET_PARAGRAPH", id: B.id, root: "OTHER-PROC" },
      );
      expect(workbenchReducer(chosen, { type: "CLOSE_ALL_TABS" }).paragraphs).toEqual({});
    });
  });

  describe("layout", () => {
    it("collapses the side bar when the current view is chosen again", () => {
      const state = apply(initialWorkbenchState, { type: "SHOW_SIDE", view: "explorer" });
      expect(state.sideVisible).toBe(false);
    });

    it("keeps the side bar open when another view is chosen", () => {
      const state = apply(initialWorkbenchState, { type: "SHOW_SIDE", view: "rules" });
      expect(state).toMatchObject({ sideVisible: true, sideView: "rules" });
    });

    it("reopens the side bar on the view that was chosen while it was hidden", () => {
      const state = apply(
        initialWorkbenchState,
        { type: "TOGGLE_SIDE" },
        { type: "SHOW_SIDE", view: "explorer" },
      );
      expect(state).toMatchObject({ sideVisible: true, sideView: "explorer" });
    });

    it("collapses the panel when the current view is chosen again", () => {
      expect(apply(initialWorkbenchState, { type: "SHOW_PANEL", view: "problems" }).panelVisible).toBe(
        false,
      );
    });

    it("clamps a restored size to its minimum", () => {
      const state = apply(initialWorkbenchState, { type: "RESTORE_SIZES", sideWidth: 10 });
      expect(state.sideWidth).toBe(SIDE_LIMITS.min);
    });

    it("keeps the current size where none was stored", () => {
      const state = apply(initialWorkbenchState, { type: "RESTORE_SIZES", sideWidth: 10 });
      expect(state.panelHeight).toBe(PANEL_LIMITS.initial);
    });

    it("rounds a fractional restored size", () => {
      expect(apply(initialWorkbenchState, { type: "RESTORE_SIZES", panelHeight: 240.6 }).panelHeight).toBe(
        241,
      );
    });
  });
});
