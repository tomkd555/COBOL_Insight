import { describe, it, expect } from "vitest";
import {
  BOTTOM_PANEL_LIMITS,
  SIDE_PANEL_LIMITS,
  activeTabOf,
  initialWorkbenchState,
  singletonTab,
  sourceTab,
  workbenchReducer,
  type WorkbenchAction,
  type WorkbenchState,
} from "./workbenchStore";

/** 一連の action を初期状態へ順に当てる。 */
function apply(...actions: readonly WorkbenchAction[]): WorkbenchState {
  return actions.reduce(workbenchReducer, initialWorkbenchState);
}

const A = sourceTab("cobol/A.cbl");
const B = sourceTab("cobol/B.cbl");

describe("タブを開く", () => {
  it("開いたタブを選択中にする", () => {
    const state = apply({ type: "OPEN_TAB", tab: A });
    expect(state.tabs.map((tab) => tab.id)).toEqual(["source:cobol/A.cbl"]);
    expect(activeTabOf(state)?.path).toBe("cobol/A.cbl");
  });

  it("資産のタブの見出しはファイル名である", () => {
    expect(sourceTab("src/cobol/A.cbl").title).toBe("A.cbl");
  });

  it("同じ資産は 2 枚開かず、選び直すだけである", () => {
    const state = apply({ type: "OPEN_TAB", tab: A }, { type: "OPEN_TAB", tab: B }, {
      type: "OPEN_TAB",
      tab: A,
    });
    expect(state.tabs).toHaveLength(2);
    expect(state.activeTabId).toBe("source:cobol/A.cbl");
  });

  it("開いてあるタブを行の指定つきで開くと、その行へ移る", () => {
    const state = apply({ type: "OPEN_TAB", tab: A }, { type: "OPEN_TAB", tab: sourceTab("cobol/A.cbl", 42) });
    expect(activeTabOf(state)?.line).toBe(42);
  });

  it("種類ごとのタブは 1 枚だけ開く", () => {
    const state = apply(
      { type: "OPEN_TAB", tab: singletonTab("graph") },
      { type: "OPEN_TAB", tab: singletonTab("graph") },
    );
    expect(state.tabs).toHaveLength(1);
    expect(state.tabs[0].title).toBe("呼出関係図");
  });
});

describe("タブを閉じる", () => {
  const opened = apply(
    { type: "OPEN_TAB", tab: A },
    { type: "OPEN_TAB", tab: B },
    { type: "OPEN_TAB", tab: singletonTab("rules") },
  );

  it("閉じたタブが選択中なら、次のタブを選ぶ", () => {
    const state = workbenchReducer({ ...opened, activeTabId: B.id }, {
      type: "CLOSE_TAB",
      id: B.id,
    });
    expect(state.activeTabId).toBe("rules");
  });

  it("末尾を閉じたときは手前を選ぶ", () => {
    const state = workbenchReducer(opened, { type: "CLOSE_TAB", id: "rules" });
    expect(state.activeTabId).toBe(B.id);
  });

  it("選択中でないタブを閉じても選択は動かない", () => {
    const state = workbenchReducer(opened, { type: "CLOSE_TAB", id: A.id });
    expect(state.activeTabId).toBe("rules");
  });

  it("最後の 1 枚を閉じると選択が無くなる", () => {
    const state = apply({ type: "OPEN_TAB", tab: A }, { type: "CLOSE_TAB", id: A.id });
    expect(state.tabs).toEqual([]);
    expect(state.activeTabId).toBeNull();
    expect(activeTabOf(state)).toBeNull();
  });

  it("開いていないタブを閉じても状態は変わらない", () => {
    expect(workbenchReducer(opened, { type: "CLOSE_TAB", id: "settings" })).toBe(opened);
  });

  it("閉じたタブの未保存の印を残さない", () => {
    const dirty = workbenchReducer(opened, { type: "SET_DIRTY", id: A.id, dirty: true });
    expect(workbenchReducer(dirty, { type: "CLOSE_TAB", id: A.id }).dirtyTabIds).toEqual([]);
  });
});

describe("タブを選ぶ", () => {
  const opened = apply({ type: "OPEN_TAB", tab: A }, { type: "OPEN_TAB", tab: B });

  it("開いてあるタブを選べる", () => {
    expect(workbenchReducer(opened, { type: "ACTIVATE_TAB", id: A.id }).activeTabId).toBe(A.id);
  });

  it("開いていないタブは選べない", () => {
    expect(workbenchReducer(opened, { type: "ACTIVATE_TAB", id: "graph" })).toBe(opened);
  });

  it("前後へ送り、端では反対の端へ回す", () => {
    expect(workbenchReducer(opened, { type: "STEP_TAB", step: 1 }).activeTabId).toBe(A.id);
    expect(workbenchReducer(opened, { type: "STEP_TAB", step: -1 }).activeTabId).toBe(A.id);
    const first = workbenchReducer(opened, { type: "ACTIVATE_TAB", id: A.id });
    expect(workbenchReducer(first, { type: "STEP_TAB", step: 1 }).activeTabId).toBe(B.id);
    expect(workbenchReducer(first, { type: "STEP_TAB", step: -1 }).activeTabId).toBe(B.id);
  });

  it("1 枚も開いていなければ送り先が無い", () => {
    expect(workbenchReducer(initialWorkbenchState, { type: "STEP_TAB", step: 1 })).toBe(
      initialWorkbenchState,
    );
  });
});

describe("未保存の印", () => {
  it("立てて外せる", () => {
    const set = apply({ type: "OPEN_TAB", tab: A }, { type: "SET_DIRTY", id: A.id, dirty: true });
    expect(set.dirtyTabIds).toEqual([A.id]);
    expect(workbenchReducer(set, { type: "SET_DIRTY", id: A.id, dirty: false }).dirtyTabIds).toEqual(
      [],
    );
  });

  it("同じ状態を重ねても増やさない", () => {
    const set = apply({ type: "OPEN_TAB", tab: A }, { type: "SET_DIRTY", id: A.id, dirty: true });
    expect(workbenchReducer(set, { type: "SET_DIRTY", id: A.id, dirty: true })).toBe(set);
  });
});

describe("パネルの開閉と寸法", () => {
  it("側パネルは同じ面を選び直すと畳む", () => {
    const hidden = workbenchReducer(initialWorkbenchState, {
      type: "SHOW_SIDE",
      view: "explorer",
    });
    expect(hidden.sideVisible).toBe(false);
    expect(workbenchReducer(hidden, { type: "SHOW_SIDE", view: "explorer" }).sideVisible).toBe(true);
  });

  it("下部パネルは面を選び直すと畳み、別の面を選ぶと開く", () => {
    const log = workbenchReducer(initialWorkbenchState, { type: "SHOW_BOTTOM", view: "log" });
    expect(log.bottomVisible).toBe(true);
    expect(log.bottomView).toBe("log");
    expect(workbenchReducer(log, { type: "SHOW_BOTTOM", view: "log" }).bottomVisible).toBe(false);
  });

  it("保存した寸法を戻し、下限を割る値は下限で丸める", () => {
    const state = workbenchReducer(initialWorkbenchState, {
      type: "RESTORE_SIZES",
      sideWidth: 10,
      bottomHeight: 420.4,
    });
    expect(state.sideWidth).toBe(SIDE_PANEL_LIMITS.min);
    expect(state.bottomHeight).toBe(420);
  });

  it("保存が無い欄は現在の寸法を保つ", () => {
    const state = workbenchReducer(initialWorkbenchState, { type: "RESTORE_SIZES" });
    expect(state.sideWidth).toBe(SIDE_PANEL_LIMITS.initial);
    expect(state.bottomHeight).toBe(BOTTOM_PANEL_LIMITS.initial);
  });

  it("寸法の操作の完了を数える(保存の合図にする)", () => {
    expect(workbenchReducer(initialWorkbenchState, { type: "COMMIT_SIZE" }).sizeCommitCount).toBe(1);
  });
});
