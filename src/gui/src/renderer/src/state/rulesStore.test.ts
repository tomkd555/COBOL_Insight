import { describe, expect, it } from "vitest";
import type { CustomRule, RulesFile } from "../../../shared/rulesFile";
import { emptyRulesFile } from "../../../shared/rulesFile";
import {
  customSavable,
  initialRulesState,
  isCustomDirty,
  rulesReducer,
  type RulesState,
} from "./rulesStore";

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

describe("rulesReducer", () => {
  describe("loading the rule file", () => {
    it("fills both panes from the same value", () => {
      const state = loaded();
      expect(state.draft).toEqual([RULE]);
      expect(JSON.parse(state.raw)).toEqual([RULE]);
      expect(isCustomDirty(state)).toBe(false);
    });
  });

  describe("editing through the form", () => {
    it("rewrites the raw pane from the draft", () => {
      const state = rulesReducer(loaded(), {
        type: "SET_DRAFT",
        draft: [{ ...RULE, name: "別の名前" }],
      });
      expect(JSON.parse(state.raw)[0].name).toBe("別の名前");
    });

    it("marks the editor dirty and savable", () => {
      const state = rulesReducer(loaded(), {
        type: "SET_DRAFT",
        draft: [{ ...RULE, name: "別の名前" }],
      });
      expect(isCustomDirty(state)).toBe(true);
      expect(customSavable(state)).toBe(true);
    });

    it("has nothing to save while a required field is empty", () => {
      const state = rulesReducer(loaded(), { type: "SET_DRAFT", draft: [{ ...RULE, name: "" }] });
      expect(isCustomDirty(state)).toBe(true);
      expect(customSavable(state)).toBe(false);
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

    it("keeps the definitions while the raw text does not parse", () => {
      const raw = rulesReducer(loaded(), { type: "SET_PANE", pane: "raw" });
      const state = rulesReducer(raw, { type: "SET_RAW", raw: "[{" });
      expect(state.rawError).not.toBeNull();
      expect(state.draft).toEqual([RULE]);
      expect(isCustomDirty(state)).toBe(true);
    });

    it("blocks the form pane while the raw text does not parse", () => {
      const raw = rulesReducer(loaded(), { type: "SET_PANE", pane: "raw" });
      const state = rulesReducer(raw, { type: "SET_RAW", raw: "[{" });
      expect(rulesReducer(state, { type: "SET_PANE", pane: "form" }).pane).toBe("raw");
    });
  });

  describe("raw JSON typed by hand with values of the wrong type", () => {
    const raw = JSON.stringify([
      {
        id: "U030",
        name: 1,
        category: 5,
        targets: "COBOL",
        message: "m",
        match: { kind: "statement", verb: "READ" },
      },
    ]);

    it("returns a state holding the definition", () => {
      const state = rulesReducer(loaded(), { type: "SET_RAW", raw });
      expect(state.rawError).toBeNull();
      expect(state.draft).toHaveLength(1);
      expect(isCustomDirty(state)).toBe(true);
    });

    it("is answered by customSavable without throwing", () => {
      const state = rulesReducer(loaded(), { type: "SET_RAW", raw });
      expect(customSavable(state)).toBe(false);
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
});

describe("customSavable", () => {
  describe("before the file is read", () => {
    it("is false for an edited, valid draft until LOAD arrives", () => {
      const edited = rulesReducer(initialRulesState, { type: "SET_DRAFT", draft: [RULE] });
      expect(isCustomDirty(edited)).toBe(true);
      expect(edited.loaded).toBe(false);
      expect(customSavable(edited)).toBe(false);
    });

    it("turns true for the same kind of edit once LOAD has arrived", () => {
      const edited = rulesReducer(loaded(), { type: "SET_DRAFT", draft: [{ ...RULE, name: "別の名前" }] });
      expect(customSavable(edited)).toBe(true);
    });
  });
});
