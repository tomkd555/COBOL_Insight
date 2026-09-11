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

  it("clears the project file when the scan wrote none", () => {
    const crashed = apply(
      initialProjectState,
      { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
      { type: "SET_INVENTORY", result: { status: "error", message: "読めません" }, dbPath: null },
    );
    // The graph and the transpile view read this path; keeping it would draw the previous run's rows.
    expect(crashed.dbPath).toBeNull();
  });

  it("drops everything read from the previous folder when another one is chosen", () => {
    const switched = apply(
      initialProjectState,
      { type: "SET_INPUT_DIR", inputDir: "C:/a" },
      { type: "SET_INVENTORY", result: { status: "ready", items: [] }, dbPath: "C:/p.db" },
      { type: "SET_FINDINGS", result: { status: "ready", items: [finding("a.cbl")] } },
      { type: "FINISH_RUN", failed: false, lint: "whole" },
      { type: "SET_INPUT_DIR", inputDir: "C:/b" },
    );
    expect(switched.inputDir).toBe("C:/b");
    expect(switched.dbPath).toBeNull();
    expect(switched.inventory.status).toBe("none");
    expect(switched.findings.status).toBe("none");
    // The SARIF pair the report is generated from was written from the previous folder.
    expect(switched.lastWholeFolderRunId).toBe(0);
    expect(switched.lastScopedRunId).toBe(0);
  });

  it("goes to results or to error according to what the run reported", () => {
    expect(apply(initialProjectState, { type: "FINISH_RUN", failed: false }).mode).toBe("results");
    expect(apply(initialProjectState, { type: "FINISH_RUN", failed: true }).mode).toBe("error");
  });

  it("clears the stale-rules note only once a lint over the whole folder has finished", () => {
    const changed = apply(initialProjectState, { type: "RULES_WRITTEN" });
    const failed = projectReducer(changed, { type: "FINISH_RUN", failed: true, lint: "whole" });
    expect(failed.rulesChangedAfterRun).toBe(true);
    // A scoped run left everything outside its scope as the previous rules found it.
    const scoped = projectReducer(changed, { type: "FINISH_RUN", failed: false, lint: "scoped" });
    expect(scoped.rulesChangedAfterRun).toBe(true);
    const whole = projectReducer(changed, { type: "FINISH_RUN", failed: false, lint: "whole" });
    expect(whole.rulesChangedAfterRun).toBe(false);
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

  it("replaces the findings inside a scope and keeps the ones outside it", () => {
    const before = apply(initialProjectState, {
      type: "SET_FINDINGS",
      result: {
        status: "ready",
        items: [finding("cobol/a.cbl", "R001"), finding("cobol/b.cbl", "R002"), finding("jcl/c.jcl", "R003")],
      },
    });

    // One asset analysed again: its finding is the new one, and the other two stand.
    const file = projectReducer(before, {
      type: "SET_FINDINGS",
      result: { status: "ready", items: [finding("cobol/a.cbl", "R009")] },
      scope: "cobol/a.cbl",
    });
    expect(artifactItems(file.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
      "cobol/b.cbl R002",
      "jcl/c.jcl R003",
      "cobol/a.cbl R009",
    ]);

    // A folder analysed again: every finding under it goes, including the one the run no longer
    // reports, and the assets outside the folder are untouched.
    const folder = projectReducer(before, {
      type: "SET_FINDINGS",
      result: { status: "ready", items: [finding("cobol/b.cbl", "R009")] },
      scope: "cobol",
    });
    expect(artifactItems(folder.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
      "jcl/c.jcl R003",
      "cobol/b.cbl R009",
    ]);
  });

  it("replaces everything when the run covered the whole folder, and when it failed", () => {
    const before = apply(initialProjectState, {
      type: "SET_SQL_FINDINGS",
      result: { status: "ready", items: [finding("cobol/a.cbl"), finding("jcl/c.jcl")] },
    });
    const whole = projectReducer(before, {
      type: "SET_SQL_FINDINGS",
      result: { status: "ready", items: [finding("cobol/a.cbl")] },
    });
    expect(artifactItems(whole.sqlFindings)).toHaveLength(1);

    const failed = projectReducer(before, {
      type: "SET_SQL_FINDINGS",
      result: { status: "error", message: "壊れた SARIF" },
    });
    expect(failed.sqlFindings.status).toBe("error");
  });

  it("keeps every finding when a scoped run fails, since it analysed nothing", () => {
    const before = apply(initialProjectState, {
      type: "SET_FINDINGS",
      result: { status: "ready", items: [finding("cobol/a.cbl"), finding("jcl/c.jcl")] },
    });
    const failed = projectReducer(before, {
      type: "SET_FINDINGS",
      result: { status: "error", message: "見つかりませんでした" },
      scope: "cobol/a.cbl",
    });
    expect(artifactItems(failed.findings)).toHaveLength(2);
  });

  it("lists a finding the scoped run reports again only once, copybooks included", () => {
    const before = apply(initialProjectState, {
      type: "SET_FINDINGS",
      result: {
        status: "ready",
        items: [finding("cobol/a.cbl", "R001"), finding("copybook/c.cpy", "R026")],
      },
    });
    // A copybook an in-scope program expands is reported again although it is outside the scope.
    const scoped = { status: "ready", items: [finding("copybook/c.cpy", "R026")] } as const;
    const once = projectReducer(before, {
      type: "SET_FINDINGS",
      result: scoped,
      scope: "cobol/a.cbl",
    });
    const twice = projectReducer(once, {
      type: "SET_FINDINGS",
      result: scoped,
      scope: "cobol/a.cbl",
    });
    expect(artifactItems(twice.findings).map((item) => `${item.file} ${item.ruleId}`)).toEqual([
      "copybook/c.cpy R026",
    ]);
  });

  it("keeps the reparse findings of a save until the scoped run that re-checks them finishes", () => {
    const saved = apply(
      initialProjectState,
      { type: "SET_FINDINGS", result: { status: "ready", items: [finding("cobol/a.cbl")] } },
      { type: "SET_SAVE_FINDINGS", path: "cobol/a.cbl", findings: [finding("cobol/a.cbl", "R002")] },
      { type: "SET_SAVE_FINDINGS", path: "cobol/b.cbl", findings: [finding("cobol/b.cbl", "R003")] },
      { type: "START_RUN", scope: "cobol/a.cbl" },
    );
    expect(artifactItems(saved.findings)).toHaveLength(1);
    // Nothing has been re-checked yet, so the parse error the save reported is still the last word.
    expect(saved.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);

    const failed = projectReducer(saved, {
      type: "SET_FINDINGS",
      result: { status: "error", message: "解析できませんでした" },
      scope: "cobol/a.cbl",
    });
    expect(failed.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl", "cobol/b.cbl"]);

    const finished = projectReducer(saved, {
      type: "SET_FINDINGS",
      result: { status: "ready", items: [] },
      scope: "cobol/a.cbl",
    });
    // The run re-checked its own scope; the reparse error of an asset it never touched stands.
    expect(finished.saveFindings.map((entry) => entry.file)).toEqual(["cobol/b.cbl"]);
  });

  it("drops the reparse findings of a save on the code result alone", () => {
    const saved = apply(initialProjectState, {
      type: "SET_SAVE_FINDINGS",
      path: "cobol/a.cbl",
      findings: [finding("cobol/a.cbl", "R002")],
    });
    // A save is reparsed as a program, so a scoped run whose code SARIF could not be read has
    // re-checked nothing, whatever its SQL SARIF says.
    const half = apply(
      saved,
      { type: "SET_FINDINGS", result: { status: "error", message: "読めませんでした" }, scope: "cobol/a.cbl" },
      { type: "SET_SQL_FINDINGS", result: { status: "ready", items: [] }, scope: "cobol/a.cbl" },
    );
    expect(half.saveFindings.map((entry) => entry.file)).toEqual(["cobol/a.cbl"]);
  });

  it("remembers which lint each run finished, so the report can say what it is behind", () => {
    const state = apply(
      initialProjectState,
      { type: "FINISH_RUN", failed: false, lint: "whole" },
      { type: "FINISH_RUN", failed: false, lint: "scoped" },
    );
    expect(state.lastScopedRunId).toBeGreaterThan(state.lastWholeFolderRunId);
    // A run over the whole folder puts the report back level with what is on screen.
    const level = projectReducer(state, { type: "FINISH_RUN", failed: false, lint: "whole" });
    expect(level.lastWholeFolderRunId).toBeGreaterThan(level.lastScopedRunId);
    // A run that failed wrote no SARIF pair and left the screen alone, so neither id moves.
    const crashed = projectReducer(level, { type: "FINISH_RUN", failed: true, lint: "scoped" });
    expect(crashed.lastScopedRunId).toBe(level.lastScopedRunId);
    expect(crashed.lastWholeFolderRunId).toBe(level.lastWholeFolderRunId);
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
          hasFix: false,
          source: "builtin",
          enabled: true,
          commands: [],
          targets: [],
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
