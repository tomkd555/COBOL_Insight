import { describe, expect, it } from "vitest";
import type { SarifFinding } from "../../../shared/ipc";
import {
  artifactCount,
  artifactItems,
  initialProjectState,
  projectReducer,
  type ArtifactState,
  type ProjectAction,
  type ProjectState,
} from "./projectStore";

function apply(state: ProjectState, ...actions: ProjectAction[]): ProjectState {
  return actions.reduce(projectReducer, state);
}

function finding(file: string, ruleId = "R001"): SarifFinding {
  return { ruleId, level: "warning", message: "m", file, startLine: 1, startColumn: 1 };
}

describe("the run lifecycle", () => {
  it("discards the previous findings when a run starts and keeps the inventory", () => {
    const withResults = apply(
      initialProjectState,
      { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
      { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
      { type: "START_RUN" },
    );
    expect(withResults.mode).toBe("running");
    expect(withResults.runStage).toBe(1);
    expect(withResults.findings.status).toBe("none");
    // The tree keeps its listing while the folder is scanned again, and after a cancelled scan.
    expect(withResults.inventory.status).toBe("ready");
    // The project file survives: it is where the next run writes, not a result of the last one.
    expect(withResults.dbPath).toBe("C:/p.db");
  });

  it("drops everything read from the previous folder when another one is chosen", () => {
    const switched = apply(
      initialProjectState,
      { type: "SET_INPUT_DIR", inputDir: "C:/a" },
      { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
      { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
      { type: "SET_INPUT_DIR", inputDir: "C:/b" },
    );
    expect(switched.inputDir).toBe("C:/b");
    expect(switched.dbPath).toBeNull();
    expect(switched.inventory.status).toBe("none");
    expect(switched.findings.status).toBe("none");
  });

  it("goes to results or to error according to what the run reported", () => {
    expect(apply(initialProjectState, { type: "FINISH_RUN", failed: false }).mode).toBe("results");
    expect(apply(initialProjectState, { type: "FINISH_RUN", failed: true }).mode).toBe("error");
  });

  it("leaves a cancelled run in results, so the finished stages stay on screen", () => {
    expect(apply(initialProjectState, { type: "START_RUN" }, { type: "CANCEL_RUN" }).mode).toBe(
      "results",
    );
  });
});

describe("artefacts", () => {
  it("keeps a failure distinct from an empty result", () => {
    const failed = apply(initialProjectState, {
      type: "SET_FINDINGS",
      result: { status: "error", message: "the SARIF is unreadable" },
    });
    expect(artifactCount(failed.findings)).toBeNull();
    expect(artifactItems(failed.findings)).toEqual([]);

    const clean = apply(initialProjectState, {
      type: "SET_FINDINGS",
      result: { status: "ready", items: [] },
    });
    expect(artifactCount(clean.findings)).toBe(0);
  });

  it("hands out the same empty list every time, so a memo keyed on it settles", () => {
    const failed: ArtifactState<SarifFinding> = { status: "error", message: "unreadable" };
    expect(artifactItems(failed)).toBe(artifactItems({ status: "none" }));
  });

  it("replaces the previous verification result when the same asset is saved again", () => {
    const state = apply(
      initialProjectState,
      { type: "SET_SAVE_FINDINGS", path: "a.cbl", findings: [finding("a.cbl", "R001")] },
      { type: "SET_SAVE_FINDINGS", path: "b.cbl", findings: [finding("b.cbl", "R002")] },
      { type: "SET_SAVE_FINDINGS", path: "a.cbl", findings: [finding("a.cbl", "R003")] },
    );
    expect(state.saveFindings.map((entry) => entry.ruleId)).toEqual(["R002", "R003"]);
  });
});

describe("the rule index", () => {
  it("indexes the catalog and maps engine severity onto the interface's", () => {
    const state = apply(initialProjectState, {
      type: "SET_RULES",
      entries: [
        {
          id: "R001",
          name: "n",
          category: "c",
          severity: "ADVISORY",
          phase: "SYNTAX",
          hasFix: false,
          source: "builtin",
          enabled: true,
          defaultEnabled: true,
          commands: [],
          targets: [],
          needs: [],
          summary: "",
          rationale: "",
          detection: "",
          remedy: "",
          badExample: "",
          goodExample: "",
        },
      ],
      ruleErrors: ["bad rule"],
    });
    expect(state.rules.byId.get("R001")?.severity).toBe("warning");
    expect(state.ruleErrors).toEqual(["bad rule"]);
  });
});

describe("the run log", () => {
  it("timestamps each line and marks the failures", () => {
    const state = apply(
      initialProjectState,
      { type: "LOG", text: "started" },
      { type: "LOG", text: "failed", failed: true },
    );
    expect(state.runLog).toHaveLength(2);
    expect(state.runLog[0].time).toMatch(/^\d{2}:\d{2}:\d{2}$/);
    expect(state.runLog[1].failed).toBe(true);
    expect(state.runLog[1].id).toBeGreaterThan(state.runLog[0].id);
  });

  it("drops the oldest lines beyond the limit", () => {
    let state = initialProjectState;
    for (let index = 0; index < 250; index += 1) {
      state = projectReducer(state, { type: "LOG", text: `line ${index}` });
    }
    expect(state.runLog).toHaveLength(200);
    expect(state.runLog[state.runLog.length - 1].text).toBe("line 249");
  });

  it("clears the log on request", () => {
    expect(
      apply(initialProjectState, { type: "LOG", text: "x" }, { type: "CLEAR_LOG" }).runLog,
    ).toEqual([]);
  });
});
