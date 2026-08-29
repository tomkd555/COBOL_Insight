import { describe, expect, it } from "vitest";
import type { CustomRule, RulesFile } from "../../../shared/rulesFile";
import { emptyRulesFile } from "../../../shared/rulesFile";
import { initialRulesState, isCustomDirty, rulesReducer, type RulesState } from "./rulesStore";

const RULE: CustomRule = {
  id: "U001",
  name: "GO TO の使用",
  message: "GO TO を使っている",
  severity: "LOW",
  match: { kind: "line", regex: "GO\\s+TO", ignoreCase: true, area: "programArea" },
};

const FILE: RulesFile = { ...emptyRulesFile(), custom: [RULE] };

function loaded(): RulesState {
  return rulesReducer(initialRulesState, { type: "LOAD", file: FILE });
}

describe("loading the rule file", () => {
  it("fills both panes from the same value", () => {
    const state = loaded();
    expect(state.draft).toEqual([RULE]);
    expect(JSON.parse(state.raw)).toEqual([RULE]);
    expect(isCustomDirty(state)).toBe(false);
  });
});

describe("editing through the form", () => {
  it("rewrites the raw pane and marks the editor dirty", () => {
    const state = rulesReducer(loaded(), {
      type: "SET_DRAFT",
      draft: [{ ...RULE, name: "別の名前" }],
    });
    expect(JSON.parse(state.raw)[0].name).toBe("別の名前");
    expect(isCustomDirty(state)).toBe(true);
  });
});

describe("editing the raw JSON", () => {
  it("carries a readable edit through to the definitions", () => {
    const state = rulesReducer(loaded(), {
      type: "SET_RAW",
      raw: JSON.stringify([{ ...RULE, severity: "HIGH" }]),
    });
    expect(state.rawError).toBeNull();
    expect(state.draft[0].severity).toBe("HIGH");
  });

  it("keeps the definitions and blocks the form while the text does not parse", () => {
    const raw = rulesReducer(loaded(), { type: "SET_PANE", pane: "raw" });
    const state = rulesReducer(raw, { type: "SET_RAW", raw: "[{" });
    expect(state.rawError).not.toBeNull();
    expect(state.draft).toEqual([RULE]);
    expect(isCustomDirty(state)).toBe(true);
    expect(rulesReducer(state, { type: "SET_PANE", pane: "form" }).pane).toBe("raw");
  });
});

describe("writing the file", () => {
  it("clears the dirty mark once the file holds what the editor shows", () => {
    const edited = rulesReducer(loaded(), {
      type: "SET_DRAFT",
      draft: [{ ...RULE, name: "別の名前" }],
    });
    const saved = rulesReducer(edited, {
      type: "SET_FILE",
      file: { ...FILE, custom: edited.draft },
    });
    expect(isCustomDirty(saved)).toBe(false);
  });
});
