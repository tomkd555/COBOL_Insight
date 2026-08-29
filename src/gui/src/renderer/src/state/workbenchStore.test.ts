import { describe, expect, it } from "vitest";
import {
  PANEL_LIMITS,
  SIDE_LIMITS,
  activeTabOf,
  draftOf,
  initialWorkbenchState,
  isTabDirty,
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

describe("tabs", () => {
  it("opens a tab and selects it", () => {
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
      { type: "CLOSE_TAB", id: A.id },
    );
    expect(state.activeTabId).toBe(B.id);
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

describe("layout", () => {
  it("collapses the side bar when the current view is chosen again", () => {
    const state = apply(initialWorkbenchState, { type: "SHOW_SIDE", view: "explorer" });
    expect(state.sideVisible).toBe(false);
  });

  it("switches to another view rather than collapsing", () => {
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

  it("counts only finished resizes, which is what triggers a save", () => {
    const state = apply(
      initialWorkbenchState,
      { type: "SET_SIDE_WIDTH", width: 300 },
      { type: "SET_SIDE_WIDTH", width: 301 },
      { type: "COMMIT_SIZE" },
    );
    expect(state.sizeCommitCount).toBe(1);
    expect(state.sideWidth).toBe(301);
  });

  it("clamps restored sizes to their minimum and keeps the current one where none was stored", () => {
    const state = apply(initialWorkbenchState, { type: "RESTORE_SIZES", sideWidth: 10 });
    expect(state.sideWidth).toBe(SIDE_LIMITS.min);
    expect(state.panelHeight).toBe(PANEL_LIMITS.initial);
  });

  it("rounds a fractional restored size", () => {
    expect(apply(initialWorkbenchState, { type: "RESTORE_SIZES", panelHeight: 240.6 }).panelHeight).toBe(
      241,
    );
  });
});
